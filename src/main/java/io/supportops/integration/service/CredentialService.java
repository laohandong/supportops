package io.supportops.integration.service;

/** 平台需再次使用的秘密存储边界；不提供 HTTP 解密接口。 */
public interface CredentialService {
    /** 保存指定用途和归属修订的秘密，返回不含明文的 UUID。 */
    String store(String purpose, String ownerId, int revision, String secret);
    /** 按用途、归属及版本核对后解密，异常只返回受控错误。 */
    String reveal(String credentialId, String purpose, String ownerId, int revision);
    /** 紧急撤销凭据，后续调用不能解密。 */
    void revoke(String credentialId);
    /** 把可解密秘密重新加密至当前主密钥；所有写入同事务。 */
    int rotate();
}
