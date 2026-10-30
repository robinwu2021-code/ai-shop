import org.springframework.security.crypto.bcrypt.BCrypt;

/** Disposable login-fixture credentials, salted independently because identity_value has a global unique key. */
public class BeautyPasswordHashes {
    public static void main(String[] args) {
        for (int i = 0; i < 5; i++) {
            System.out.println(BCrypt.hashpw("admin123", BCrypt.gensalt()));
        }
    }
}
