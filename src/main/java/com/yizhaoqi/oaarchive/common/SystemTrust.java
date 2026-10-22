package com.yizhaoqi.oaarchive.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * HTTPS 证书信任：先用 Java 自带的证书库，验证不过时再用 Windows 系统证书库（和浏览器信任的一样）。
 * <p>
 * 用途：公司网络的 HTTPS 审查、Watt Toolkit 这类加速工具会用自己的根证书重新签发网站证书，
 * 根证书只装在 Windows 证书库里，Java 自带的证书库不认识，联网检查更新和 AI 助手就会连不上。
 * 证书链和主机名照样校验，不会跳过验证。非 Windows 系统（如 Docker）只用 Java 自带的证书库。
 */
public final class SystemTrust {

    private static final Logger log = LoggerFactory.getLogger(SystemTrust.class);
    private static volatile SSLContext context;

    private SystemTrust() {
    }

    public static SSLContext context() {
        SSLContext c = context;
        if (c == null) {
            synchronized (SystemTrust.class) {
                if (context == null) {
                    context = build();
                }
                c = context;
            }
        }
        return c;
    }

    private static SSLContext build() {
        try {
            List<X509ExtendedTrustManager> tms = new ArrayList<>();
            tms.add(trustManager(null)); // Java 自带的 cacerts
            try {
                KeyStore win = KeyStore.getInstance("Windows-ROOT");
                win.load(null, null);
                tms.add(trustManager(win));
            } catch (GeneralSecurityException | java.io.IOException e) {
                log.debug("没有可用的 Windows 系统证书库，只使用 Java 自带证书：{}", e.toString());
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new Composite(tms)}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            log.warn("初始化证书信任失败，使用默认设置：{}", e.toString());
            try {
                return SSLContext.getDefault();
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static X509ExtendedTrustManager trustManager(KeyStore ks) throws GeneralSecurityException {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager tm : f.getTrustManagers()) {
            if (tm instanceof X509ExtendedTrustManager x) {
                return x;
            }
        }
        throw new GeneralSecurityException("没有找到 X509 证书校验器");
    }

    /** 依次尝试每个证书库，有一个验证通过就算通过；都不通过时抛出第一个错误 */
    static final class Composite extends X509ExtendedTrustManager {
        private final List<X509ExtendedTrustManager> tms;

        Composite(List<X509ExtendedTrustManager> tms) {
            this.tms = List.copyOf(tms);
        }

        private interface Check {
            void run(X509ExtendedTrustManager tm) throws CertificateException;
        }

        private void any(Check check) throws CertificateException {
            CertificateException first = null;
            for (X509ExtendedTrustManager tm : tms) {
                try {
                    check.run(tm);
                    return;
                } catch (CertificateException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            throw first != null ? first : new CertificateException("没有可用的证书库");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType, engine));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            any(tm -> tm.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            tms.get(0).checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            tms.get(0).checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            tms.get(0).checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return tms.stream().flatMap(tm -> Arrays.stream(tm.getAcceptedIssuers())).toArray(X509Certificate[]::new);
        }
    }
}
