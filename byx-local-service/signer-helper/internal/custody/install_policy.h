#ifndef BYX_INSTALL_POLICY_H
#define BYX_INSTALL_POLICY_H
#include <sys/stat.h>
#include <sys/acl.h>
#include <fcntl.h>
#include <errno.h>
#include <limits.h>
#include <string.h>
#include <unistd.h>

/* Development installs trust the owner UID. Other writers, unsafe ACLs and symlinks are refused.
 * A root-owned install is required to protect against an adversarial owner modifying files after seal validation. */
static int install_path_ok(const char *exe, const char *bundle) {
    char path[PATH_MAX];
    if (strlcpy(path, exe, sizeof path) >= sizeof path || path[0] != '/') return 0;
    for (;;) {
        int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
        filesec_t fs = filesec_init();
        struct stat st;
        int present = 0;
        if (fd < 0 || !fs || fstatx_np(fd, &st, fs) != 0 || filesec_query_property(fs, FILESEC_ACL, &present) != 0) {
            if (fd >= 0) close(fd);
            if (fs) filesec_free(fs);
            return 0;
        }
        close(fd);
        if ((!S_ISDIR(st.st_mode) && !S_ISREG(st.st_mode)) || (st.st_uid != 0 && st.st_uid != geteuid())) { filesec_free(fs); return 0; }
        int trusted_sticky_ancestor = S_ISDIR(st.st_mode) && st.st_uid == 0 && (st.st_mode & S_ISVTX) && strlen(path) < strlen(bundle);
        if ((st.st_mode & 0022) && !trusted_sticky_ancestor) { filesec_free(fs); return 0; }
        if (present) {
            acl_t acl = NULL;
            if (filesec_get_property(fs, FILESEC_ACL, &acl) != 0 || !acl || acl_valid(acl) != 0) { filesec_free(fs); if (acl) acl_free(acl); return 0; }
            acl_entry_t entry;
            int rc = acl_get_entry(acl, ACL_FIRST_ENTRY, &entry);
            while (rc == 0) {
                acl_tag_t tag;
                acl_permset_t perms;
                if (acl_get_tag_type(entry, &tag) != 0 || acl_get_permset(entry, &perms) != 0) { acl_free(acl); filesec_free(fs); return 0; }
                if (tag == ACL_EXTENDED_ALLOW && (acl_get_perm_np(perms, ACL_WRITE_DATA) || acl_get_perm_np(perms, ACL_APPEND_DATA)
                        || acl_get_perm_np(perms, ACL_DELETE) || acl_get_perm_np(perms, ACL_DELETE_CHILD)
                        || acl_get_perm_np(perms, ACL_WRITE_ATTRIBUTES) || acl_get_perm_np(perms, ACL_WRITE_EXTATTRIBUTES)
                        || acl_get_perm_np(perms, ACL_WRITE_SECURITY) || acl_get_perm_np(perms, ACL_CHANGE_OWNER))) { acl_free(acl); filesec_free(fs); return 0; }
                errno = 0;
                rc = acl_get_entry(acl, ACL_NEXT_ENTRY, &entry);
            }
            /* Darwin returns -1/EINVAL at end (unlike Linux); acl_valid above disambiguates malformed ACLs. */
            int exhausted = rc == -1 && errno == EINVAL;
            acl_free(acl);
            if (!exhausted) { filesec_free(fs); return 0; }
        }
        filesec_free(fs);
        if (strcmp(path, "/") == 0) return 1;
        char *slash = strrchr(path, '/');
        if (!slash) return 0;
        if (slash == path) path[1] = 0; else *slash = 0;
    }
}

#endif
