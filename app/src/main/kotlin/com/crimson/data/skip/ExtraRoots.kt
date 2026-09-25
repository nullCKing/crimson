package com.crimson.data.skip

import android.content.Context
import android.util.Log
import com.crimson.R
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Let's Encrypt's roots, for the services that use them.
 *
 * Both intro databases have Let's Encrypt certificates. ISRG Root X1 is missing from Android
 * before 7.1 (Fire OS 5 is 5.1) and ISRG Root X2 from everything before 14, so without these the
 * requests fail on the user's box and on most TVs. The system's roots are asked first; the two
 * bundled ones (`res/raw/extra_roots.pem`, from Mozilla's list) only when the system says no.
 */
object ExtraRoots {

    fun apply(builder: OkHttpClient.Builder, context: Context): OkHttpClient.Builder = runCatching {
        val system = trustManager(null)
        val extra = trustManager(bundled(context))
        val combined = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    extra.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers + extra.acceptedIssuers
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(combined), null) }
        builder.sslSocketFactory(ssl.socketFactory, combined)
    }.onFailure { Log.w("CrimsonRoots", "could not add bundled roots", it) }.getOrDefault(builder)

    private fun bundled(context: Context): KeyStore {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        context.resources.openRawResource(R.raw.extra_roots).use { input ->
            CertificateFactory.getInstance("X.509").generateCertificates(input).forEachIndexed { i, cert ->
                store.setCertificateEntry("extra$i", cert)
            }
        }
        return store
    }

    private fun trustManager(store: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
