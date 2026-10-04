"""Extract static reference pages outside Git; never install bundled fonts/assets."""
import argparse
import base64
import gzip
import hashlib
import json
import re
from pathlib import Path


def extract(artifact, output):
    source = artifact.read_text()

    def data(kind):
        match = re.search(r'<script type="__bundler/' + kind + r'">(.*?)</script>', source, re.S)
        if not match:
            raise ValueError(f"Missing bundle {kind}")
        return json.loads(match.group(1))

    manifest, template = data("manifest"), data("template")
    output.mkdir(parents=True, exist_ok=True)
    pages = []
    for uid, title in re.findall(r'about:blank#([^" ]+)" title="([^"]+)', template):
        entry = manifest[uid]
        raw = base64.b64decode(entry["data"])
        if entry.get("compressed"):
            raw = gzip.decompress(raw)
        bundle = raw.decode()
        match = re.search(r'<script type="__bundler/template">(.*?)</script>', bundle, re.S)
        page = json.loads(match.group(1)) if match else bundle
        name = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")
        # Static visual reference: omit scripts and embedded font faces from QA copies.
        page = re.sub(r"<script.*?</script>", "", page, flags=re.S)
        page = re.sub(r"@font-face\s*\{.*?\}", "", page, flags=re.S)
        (output / f"{name}.html").write_text(page)
        pages.append({"title": title, "file": f"{name}.html"})
    report = {"artifact_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(), "pages": pages}
    (output / "audit.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Extracted {len(pages)} pages to {output}; SHA-256 {report['artifact_sha256']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    extract(args.artifact, args.output)
