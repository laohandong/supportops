package io.supportops.integration.service.support;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只从私有文件加载企业CA；保留系统CA和TLS主机名核验，不使用信任全部证书。 */
@Component
public final class EnterpriseTlsTrust {
    /** 在现场网络组件初始化前固定信任库，密码不放进JVM参数或启动日志。 */
    public EnterpriseTlsTrust(@Value("${supportops.enterprise.enabled:false}") boolean enabled,
            @Value("${supportops.enterprise.trust-store-file:}") String storeFile,
            @Value("${supportops.enterprise.trust-store-password-file:}") String passwordFile) {
        if (!enabled || storeFile.isBlank()) { return; }
        try {
            Path storePath = Path.of(storeFile); Path passwordPath = Path.of(passwordFile);
            if (Files.size(storePath) > 10 * 1024 * 1024 || Files.size(passwordPath) > 1024) { throw new IllegalArgumentException(); }
            char[] password = Files.readString(passwordPath).strip().toCharArray();
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(storePath)) { store.load(input, password); }
            finally { Arrays.fill(password, '\0'); }
            TrustManagerFactory enterprise = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            enterprise.init(store);
            TrustManagerFactory system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            system.init((KeyStore) null);
            X509TrustManager extra = manager(enterprise); X509TrustManager defaults = manager(system);
            X509TrustManager combined = new X509TrustManager() {
                /** 客户端校验沿用系统CA，不扩大入站鉴权。 */
                @Override public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                    defaults.checkClientTrusted(chain,type);
                }
                /** 企业CA失败时检查系统CA，两者均失败则拒绝连接。 */
                @Override public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                    try { extra.checkServerTrusted(chain,type); }
                    catch (CertificateException exception) { defaults.checkServerTrusted(chain,type); }
                }
                /** 返回受信任发行者，不含任何私钥。 */
                @Override public X509Certificate[] getAcceptedIssuers() {
                    X509Certificate[] left = extra.getAcceptedIssuers(); X509Certificate[] right = defaults.getAcceptedIssuers();
                    X509Certificate[] values = Arrays.copyOf(left,left.length+right.length);
                    System.arraycopy(right,0,values,left.length,right.length); return values;
                }
            };
            SSLContext context = SSLContext.getInstance("TLS"); context.init(null,new TrustManager[]{combined},null);
            SSLContext.setDefault(context);
        } catch (Exception exception) { throw new IllegalStateException("ENTERPRISE_TRUST_STORE_INVALID"); }
    }
    /** 只接受标准X509校验器，缺失时阻止企业网络初始化。 */
    private X509TrustManager manager(TrustManagerFactory factory) {
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) { return x509; }
        }
        throw new IllegalStateException("ENTERPRISE_TRUST_MANAGER_MISSING");
    }
}
