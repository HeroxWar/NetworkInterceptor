package me.lucko.networkinterceptor.interceptors;

import me.lucko.networkinterceptor.InterceptEvent;
import me.lucko.networkinterceptor.common.NetworkInterceptorPlugin;
import me.lucko.networkinterceptor.utils.SneakyThrow;

import java.io.IOException;
import java.net.*;
import java.util.List;

public class ProxySelectorInterceptor<PLUGIN> implements Interceptor {
    // private static final List<Proxy> DUMMY_PROXY = new DummyProxyList();

    private final NetworkInterceptorPlugin<PLUGIN> plugin;

    public ProxySelectorInterceptor(NetworkInterceptorPlugin<PLUGIN> plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        ProxySelector selector = ProxySelector.getDefault();
        if (selector instanceof LoggingSelector) {
            return;
        }

        this.plugin.getLogger().info("[ProxySelectorInterceptor] Replacing '" + selector.getClass().getName()
                + "' selector with logged variant.");
        ProxySelector.setDefault(new LoggingSelector<PLUGIN>(selector));
    }


    @Override
    public void disable() {
        ProxySelector selector = ProxySelector.getDefault();
        if (selector instanceof LoggingSelector) {
            @SuppressWarnings("unchecked")
            LoggingSelector<PLUGIN> logSelector = ((LoggingSelector<PLUGIN>) selector);
            ProxySelector.setDefault(logSelector.delegate);
        }
    }

    private final class LoggingSelector<U> extends ProxySelector {
        private final ProxySelector delegate;

        private LoggingSelector(ProxySelector delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri.getHost();
            StackTraceElement[] trace = new Exception().getStackTrace();

            String x = "";
            x += " String_URI: " + uri.toString();
            x += "\n  Scheme: " + uri.getScheme();
            x += "\n  Host: " + host;
            x += "\n  Port: " + (uri.getPort() == -1? "default" : uri.getPort());
            x += "\n  Path: " + uri.getRawPath();
            x += "\n  Query: " + uri.getRawQuery();
            x += "\n  Fragment: " + uri.getRawFragment();
            x += "\n  RawSchemeSpecificPart:" + uri.getRawSchemeSpecificPart();
            x += "\n  Authority:" + uri.getRawAuthority();
            x += "\n  UserInfo:" + uri.getRawUserInfo();


            InterceptEvent<PLUGIN> event = new InterceptEvent<>(host, trace, plugin.getPlatformType(), x + "  ");

            boolean blocked = ProxySelectorInterceptor.this.plugin.getDelegate().shouldBlock(event);

            if (blocked) {
                ProxySelectorInterceptor.this.plugin.getDelegate().logBlock(event);
                SneakyThrow.sneakyThrow(new ConnectionBlockedException("ProxySelector"));
                throw new AssertionError();
            } else {
                ProxySelectorInterceptor.this.plugin.getDelegate().logAttempt(event);
            }

            return this.delegate.select(uri);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            this.delegate.connectFailed(uri, sa, ioe);
        }
    }

    /*
     * private static final class DummyProxyList extends AbstractList<Proxy> {
     *
     * @Override
     * public Proxy get(int index) {
     * SneakyThrow.sneakyThrow(new IOException("Connection not allowed"));
     * throw new AssertionError();
     * }
     *
     * @Override
     * public int size() {
     * return 1;
     * }
     * }
     */
}
