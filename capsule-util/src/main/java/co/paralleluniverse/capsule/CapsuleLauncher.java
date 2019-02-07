/*
 * Capsule
 * Copyright (c) 2014-2015, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are licensed under the terms 
 * of the Eclipse Public License v1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package co.paralleluniverse.capsule;

import static co.paralleluniverse.common.Exceptions.rethrow;
import co.paralleluniverse.common.JarClassLoader;
import co.paralleluniverse.common.JarInputStream;
import java.io.IOException;
import sun.misc.Unsafe;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Manifest;

/**
 * Provides methods for loading, inspecting, and launching capsules.
 *
 * @author pron
 */
public final class CapsuleLauncher {
    private static final String CAPSULE_CLASS_NAME = "Capsule";
    private static final String OPT_JMX_REMOTE = "com.sun.management.jmxremote";
    private static final String ATTR_MAIN_CLASS = "Main-Class";
    private static final String PROP_MODE = "capsule.mode";

    private final Path jarFile;
    private final Class capsuleClass;
    private Properties properties;

    public CapsuleLauncher(Path jarFile) throws IOException {
        this.jarFile = jarFile;
        this.capsuleClass = loadCapsuleClass(jarFile);
        setProperties(null);
    }

    static {
        disableWarning();
    }

    private static void disableWarning() {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Unsafe u = (Unsafe) theUnsafe.get(null);

            // List<String> clazzes = Arrays.asList("com.sun.jmx.mbeanserver.JmxMBeanServer.mbsInterceptor",  "com.fasterxml.jackson.module.afterburner.util.MyClassLoader");

            Class cls = Class.forName("com.sun.jmx.mbeanserver.JmxMBeanServer");
            Field logger = cls.getDeclaredField("mbsInterceptor");
            u.putObjectVolatile(cls, u.staticFieldOffset(logger), null);

        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Sets the Java homes that will be used by the capsules created by {@code newCapsule}.
     *
     * @param javaHomes a map from Java version strings to their respective JVM installation paths
     * @return {@code this}
     */
    public CapsuleLauncher setJavaHomes(Map<String, List<Path>> javaHomes) {
        final Field homes = getCapsuleField("JAVA_HOMES");
        if (homes != null)
            set(null, homes, javaHomes);
        return this;
    }

    /**
     * Sets the properties for the capsules created by {@code newCapsule}
     *
     * @param properties the properties
     * @return {@code this}
     */
    public CapsuleLauncher setProperties(Properties properties) {
        this.properties = properties != null ? properties : new Properties(System.getProperties());
        set(null, getCapsuleField("PROPERTIES"), this.properties);
        return this;
    }

    /**
     * Sets a property for the capsules created by {@code newCapsule}
     *
     * @param property the name of the property
     * @param value    the property's value
     * @return {@code this}
     */
    public CapsuleLauncher setProperty(String property, String value) {
        if (value != null)
            properties.setProperty(property, value);
        else
            properties.remove(property);
        return this;
    }

    /**
     * Sets the location of the cache directory for the capsules created by {@code newCapsule}
     *
     * @param dir the cache directory
     * @return {@code this}
     */
    public CapsuleLauncher setCacheDir(Path dir) {
        set(null, getCapsuleField("CACHE_DIR"), dir);
        return this;
    }

    /**
     * Creates a new capsule.
     */
    public Capsule newCapsule() {
        return newCapsule(null, null);
    }

    /**
     * Creates a new capsule
     *
     * @param mode the capsule mode
     * @return the capsule.
     */
    public Capsule newCapsule(String mode) {
        return newCapsule(mode, null);
    }

    /**
     * Creates a new capsule
     *
     * @param wrappedJar a path to a capsule JAR that will be launched (wrapped) by the empty capsule in {@code jarFile}
     *                   or {@code null} if no wrapped capsule is wanted
     * @return the capsule.
     */
    public Capsule newCapsule(Path wrappedJar) {
        return newCapsule(null, wrappedJar);
    }

    /**
     * Creates a new capsule
     *
     * @param mode       the capsule mode, or {@code null} for the default mode
     * @param wrappedJar a path to a capsule JAR that will be launched (wrapped) by the empty capsule in {@code jarFile}
     *                   or {@code null} if no wrapped capsule is wanted
     * @return the capsule.
     */
    public Capsule newCapsule(String mode, Path wrappedJar) {
        final String oldMode = properties.getProperty(PROP_MODE);
        final ClassLoader oldCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(capsuleClass.getClassLoader());
        try {
            setProperty(PROP_MODE, mode);

            final Constructor<?> ctor = accessible(capsuleClass.getDeclaredConstructor(Path.class));
            final Object capsule = ctor.newInstance(jarFile);

            if (wrappedJar != null) {
                final Method setTarget = accessible(capsuleClass.getDeclaredMethod("setTarget", Path.class));
                setTarget.invoke(capsule, wrappedJar);
            }

            return wrap(capsule);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Could not create capsule instance.", e);
        } finally {
            setProperty(PROP_MODE, oldMode);
            Thread.currentThread().setContextClassLoader(oldCl);
        }
    }

    private static Class<?> loadCapsuleClass(Path jarFile) throws IOException {
        final Manifest mf;
        try (JarInputStream jis = new JarInputStream(Files.newInputStream(jarFile))) {
            mf = jis.getManifest();
        }

        final ClassLoader cl = new JarClassLoader(jarFile, true);
        final Class<?> clazz = loadCapsuleClass(mf, cl);
        if (clazz == null)
            throw new RuntimeException(jarFile + " does not appear to be a valid capsule.");
        return clazz;
    }

    private static Class<?> loadCapsuleClass(Manifest mf, ClassLoader cl) {
        final String mainClass = mf.getMainAttributes() != null ? mf.getMainAttributes().getValue(ATTR_MAIN_CLASS) : null;
        if (mainClass == null)
            return null;

        try {
            Class<?> clazz = cl.loadClass(mainClass);
            if (!isCapsuleClass(clazz))
                clazz = null;
            return clazz;
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static boolean isCapsuleClass(Class<?> clazz) {
        if (clazz == null)
            return false;
        return getActualCapsuleClass(clazz) != null;
    }

    private static Capsule wrap(Object capsule) {
        return (Capsule) Proxy.newProxyInstance(CapsuleLauncher.class.getClassLoader(), new Class<?>[]{Capsule.class}, new CapsuleAccess(capsule));
    }

    private static class CapsuleAccess implements InvocationHandler {
        private final Object capsule;
        private final Class<?> clazz;

        public CapsuleAccess(Object capsule) {
            this.capsule = capsule;
            this.clazz = capsule.getClass();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass().equals(Object.class) && method.getName().equals("equals")) {
                final Object other = args[0];
                if (other == this)
                    return true;
                if (!(other instanceof CapsuleAccess))
                    return false;
                return call(method, args);
            }

            try {
                return call(method, args);
            } catch (NoSuchMethodException e) {
                switch (method.getName()) {
                    case "getVersion":
                        return get("VERSION");
                    case "getProperties":
                        return get("PROPERTIES");
                    case "getAttribute":
                        return getMethod(clazz, "getAttribute", Map.Entry.class).invoke(capsule, ((Attribute) args[0]).toEntry());
                    case "hasAttribute":
                        return getMethod(clazz, "hasAttribute", Map.Entry.class).invoke(capsule, ((Attribute) args[0]).toEntry());
                    default:
                        throw new UnsupportedOperationException("Capsule " + clazz + " does not support this operation");
                }
            }
        }

        private Object call(Method method, Object[] args) throws NoSuchMethodException {
            final Method m = getMethod(clazz, method.getName(), method.getParameterTypes());
            if (m == null)
                throw new NoSuchMethodException();
            return CapsuleLauncher.invoke(capsule, m, args);
        }

        private Object get(String field) {
            final Field f = getField(clazz, field);
            if (f == null)
                throw new UnsupportedOperationException("Capsule " + clazz + " does not contain the field " + field);
            return CapsuleLauncher.get(capsule, f);
        }
    }

    /**
     * Returns all known Java installations
     *
     * @return a map from the version strings to their respective paths of the Java installations.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, List<Path>> findJavaHomes() {
        try {
            return (Map<String, List<Path>>) accessible(Class.forName(CAPSULE_CLASS_NAME).getDeclaredMethod("getJavaHomes")).invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Adds an option to the JVM arguments to enable JMX connection
     *
     * @param jvmArgs the JVM args
     * @return a new list of JVM args
     */
    public static List<String> enableJMX(List<String> jvmArgs) {
        final String arg = "-D" + OPT_JMX_REMOTE;
        if (jvmArgs.contains(arg))
            return jvmArgs;
        final List<String> cmdLine2 = new ArrayList<>(jvmArgs);
        cmdLine2.add(arg);
        return cmdLine2;
    }

    private Field getCapsuleField(String name) {
        return getField(getActualCapsuleClass(capsuleClass), name);
    }

    //<editor-fold defaultstate="collapsed" desc="Reflection">
    /////////// Reflection ///////////////////////////////////
    private static Method getMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return getMethod0(clazz, name, paramTypes);
        }
    }

    private static Method getMethod0(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return accessible(clazz.getDeclaredMethod(name, paramTypes));
        } catch (NoSuchMethodException e) {
            return clazz.getSuperclass() != null ? getMethod0(clazz.getSuperclass(), name, paramTypes) : null;
        }
    }

    private static Field getField(Class<?> clazz, String name) {
        try {
            return accessible(clazz.getDeclaredField(name));
        } catch (NoSuchFieldException e) {
            return clazz.getSuperclass() != null ? getField(clazz.getSuperclass(), name) : null;
        }
    }

    private static Class<?> getActualCapsuleClass(Class<?> clazz) {
        while (clazz != null && !clazz.getName().equals(CAPSULE_CLASS_NAME))
            clazz = clazz.getSuperclass();
        return clazz;
    }

    private static Object invoke(Object obj, Method method, Object... params) {
        try {
            return method.invoke(obj, params);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    private static <T extends AccessibleObject> T accessible(T x) {
        if (!x.isAccessible())
            x.setAccessible(true);
        return x;
    }

    private static Object get(Object obj, Field field) {
        try {
            return field.get(obj);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    private static void set(Object obj, Field field, Object value) {
        try {
            field.set(obj, value);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }
    //</editor-fold>
}
