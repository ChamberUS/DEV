package panel.user;

import java.util.List;
import java.util.Optional;

public interface UserRepository {
    long count();

    Optional<User> findById(long id);

    Optional<User> findByUsernameOrEmail(String identifier);

    List<User> findAll();

    User insert(User user);

    void update(User user);

    long countActiveAdmins();
}
