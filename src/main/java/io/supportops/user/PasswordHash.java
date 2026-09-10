package io.supportops.user;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
/** 使用随机盐与 PBKDF2-HMAC-SHA256 保存密码，不保存明文。 */
public final class PasswordHash {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 600_000;
    /** 禁止实例化密码工具。 */
    private PasswordHash() {}
    /** 为每个密码生成独立的 128 位随机盐。 */
    public static String encode(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }
    /** 常量时间比较摘要；损坏的摘要拒绝认证。 */
    public static boolean matches(String password, String stored) {
        try {
            String[] parts = stored.split(":");
            return MessageDigest.isEqual(Base64.getDecoder().decode(parts[2]),
                    derive(password, Base64.getDecoder().decode(parts[1]), Integer.parseInt(parts[0])));
        } catch (IllegalArgumentException | IndexOutOfBoundsException exception) {
            return false;
        }
    }
    /** 执行派生并清理密码规格中的字符副本。 */
    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PASSWORD_HASH_UNAVAILABLE");
        } finally {
            spec.clearPassword();
        }
    }
}
