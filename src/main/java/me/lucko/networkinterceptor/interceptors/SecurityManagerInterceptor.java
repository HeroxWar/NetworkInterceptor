package me.lucko.networkinterceptor.interceptors;

import me.lucko.networkinterceptor.InterceptEvent;
import me.lucko.networkinterceptor.common.NetworkInterceptorPlugin;
import me.lucko.networkinterceptor.utils.SneakyThrow;

import java.security.Permission;

public class SecurityManagerInterceptor<PLUGIN> extends SecurityManager implements Interceptor {
    private final NetworkInterceptorPlugin<PLUGIN> plugin;

    private boolean enabled = true;

    public SecurityManagerInterceptor(NetworkInterceptorPlugin<PLUGIN> plugin) {
        this.plugin = plugin;
    }

    @Override
    public void enable() {
        System.setSecurityManager(this);
    }

    @Override
    public void disable() {
        enabled = false;

        System.setSecurityManager(null);
    }

    @Override
    public void checkConnect(String host, int port, Object context) {
        StackTraceElement[] trace = new Exception().getStackTrace();
        InterceptEvent<PLUGIN> event = new InterceptEvent<>(host, trace, plugin.getPlatformType(), context);

        boolean blocked = this.plugin.getDelegate().shouldBlock(event);

        // this.plugin.getDelegate().logAttempt(event);

        if (blocked) {
            this.plugin.getDelegate().logBlock(event);
            SneakyThrow.sneakyThrow(new ConnectionBlockedException("SecurityManager"));
            throw new AssertionError();
        } else {
            this.plugin.getDelegate().logAttempt(event);
        }
    }

    /*@Override
    public void checkConnect(String host, int port, Object context) {
        checkConnect(host, port, context);
    }*/

    @Override
    public void checkPermission(Permission perm) {
        String name = perm.getName();
        if (name == null) {
            return;
        }

        if (this.enabled && name.equals("setSecurityManager")) {
            throw new SecurityException("Cannot replace the security manager.");
        }
    }

    @Override
    public void checkPermission(Permission perm, Object context) {
        checkPermission(perm);
    }
}
