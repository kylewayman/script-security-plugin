/*
 * The MIT License
 *
 * Copyright 2014 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.ClassUtils;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * A whitelist based on listing signatures and searching them.
 */
public abstract class EnumeratingWhitelist extends Whitelist {

    protected abstract List<MethodSignature> methodSignatures();

    protected abstract List<NewSignature> newSignatures();

    protected abstract List<MethodSignature> staticMethodSignatures();

    protected abstract List<FieldSignature> fieldSignatures();

    protected abstract List<FieldSignature> staticFieldSignatures();

    ConcurrentHashMap<String, Boolean> permittedCache = new ConcurrentHashMap<String, Boolean>();  // Not private to facilitate testing

    private void cacheSignatureList(List<Signature> ...sigs) {
        for (List<Signature> list : sigs) {
            for (Signature s : list) {
                if (!s.isWildcard()) { // Cache entries for wildcard signatures will never be accessed and just waste space
                    permittedCache.put(s.toString(), Boolean.TRUE);
                }
            }
        }
    }

    /** Prepopulates the "permitted" cache. */
    public void precache() {
        cacheSignatureList((List)methodSignatures(), (List)(newSignatures()),
                           (List)(staticMethodSignatures()), (List)(fieldSignatures()),
                           (List)(staticFieldSignatures()));
    }

    /** Frees up memory used for the cache. */
    void clearCache() {
        this.permittedCache.clear();
        this.permittedCache = new ConcurrentHashMap<String, Boolean>();
    }

    @Override public final boolean permitsMethod(Method method, Object receiver, Object[] args) {
        String key = Whitelist.canonicalMethodSig(method);
        Boolean b = permittedCache.get(key);
        if (b != null) {
            return b;
        }

        boolean output = false;
        for (MethodSignature s : methodSignatures()) {
            if (s.matches(method)) {
                output = true;
                break;
            }
        }
        permittedCache.put(key, output);
        return output;
    }

    @Override public final boolean permitsConstructor(Constructor<?> constructor, Object[] args) {
        String key = Whitelist.canonicalConstructorSig(constructor);
        Boolean b = permittedCache.get(key);
        if (b != null) {
            return b;
        }

        boolean output = false;
        for (NewSignature s : newSignatures()) {
            if (s.matches(constructor)) {
                output = true;
                break;
            }
        }
        permittedCache.put(key, output);
        return output;
    }

    @Override public final boolean permitsStaticMethod(Method method, Object[] args) {
        String key = Whitelist.canonicalStaticMethodSig(method);
        Boolean b = permittedCache.get(key);
        if (b != null) {
            return b;
        }

        boolean output = false;
        for (MethodSignature s : staticMethodSignatures()) {
            if (s.matches(method)) {
                output = true;
                break;
            }
        }
        permittedCache.put(key, output);
        return output;
    }

    @Override public final boolean permitsFieldGet(Field field, Object receiver) {
        String key = Whitelist.canonicalFieldSig(field);
        Boolean b = permittedCache.get(key);
        if (b != null) {
            return b;
        }

        boolean output = false;
        for (FieldSignature s : fieldSignatures()) {
            if (s.matches(field)) {
                output = true;
                break;
            }
        }
        permittedCache.put(key, output);
        return output;
    }

    @Override public final boolean permitsFieldSet(Field field, Object receiver, Object value) {
        return permitsFieldGet(field, receiver);
    }

    @Override public final boolean permitsStaticFieldGet(Field field) {
        String key = Whitelist.canonicalStaticFieldSig(field);
        Boolean b = permittedCache.get(key);
        if (b != null) {
            return b;
        }

        boolean output = false;
        for (FieldSignature s : staticFieldSignatures()) {
            if (s.matches(field)) {
                output = true;
                break;
            }
        }
        permittedCache.put(key, output);
        return output;
    }

    @Override public final boolean permitsStaticFieldSet(Field field, Object value) {
        return permitsStaticFieldGet(field);
    }

    public static @Nonnull String getName(@Nonnull Class<?> c) {
        Class<?> e = c.getComponentType();
        if (e == null) {
            return c.getName();
        } else {
            return getName(e) + "[]";
        }
    }

    public static @Nonnull String getName(@CheckForNull Object o) {
        return o == null ? "null" : getName(o.getClass());
    }

    private static boolean is(String thisIdentifier, String identifier) {
        return thisIdentifier.equals("*") || identifier.equals(thisIdentifier);
    }

    public static abstract class Signature implements Comparable<Signature> {
        /** Form as in {@link StaticWhitelist} entries. */
        @Override public abstract String toString();

        abstract String signaturePart();
        @Override public int compareTo(Signature o) {
            int r = signaturePart().compareTo(o.signaturePart());
            return r != 0 ? r : toString().compareTo(o.toString());
        }
        @Override public boolean equals(Object obj) {
            return obj != null && obj.getClass() == getClass() && toString().equals(obj.toString());
        }
        @Override public int hashCode() {
            return toString().hashCode();
        }
        abstract boolean exists() throws Exception;
        final Class<?> type(String name) throws Exception {
            return ClassUtils.getClass(name);
        }
        final Class<?>[] types(String[] names) throws Exception {
            Class<?>[] r = new Class<?>[names.length];
            for (int i = 0; i < names.length; i++) {
                r[i] = type(names[i]);
            }
            return r;
        }

        public boolean isWildcard() {
            return false;
        }
    }

    public static class MethodSignature extends Signature {
        final String receiverType, method;
        final String[] argumentTypes;
        public MethodSignature(String receiverType, String method, String[] argumentTypes) {
            this.receiverType = receiverType;
            this.method = method;
            this.argumentTypes = argumentTypes.clone();
        }
        public MethodSignature(Class<?> receiverType, String method, Class<?>... argumentTypes) {
            this(getName(receiverType), method, argumentTypes(argumentTypes));
        }
        boolean matches(Method m) {
            return is(method, m.getName()) && getName(m.getDeclaringClass()).equals(receiverType) && Arrays.equals(argumentTypes(m.getParameterTypes()), argumentTypes);
        }
        @Override public String toString() {
            return "method " + signaturePart();
        }
        @Override String signaturePart() {
            return joinWithSpaces(new StringBuilder(receiverType).append(' ').append(method), argumentTypes).toString();
        }
        @Override boolean exists() throws Exception {
            return exists(type(receiverType), true);
        }
        // Cf. GroovyCallSiteSelector.visitTypes.
        @SuppressWarnings("InfiniteRecursion")
        private boolean exists(Class<?> c, boolean start) throws Exception {
            Class<?> s = c.getSuperclass();
            if (s != null && exists(s, false)) {
                return !start;
            }
            for (Class<?> i : c.getInterfaces()) {
                if (exists(i, false)) {
                    return !start;
                }
            }
            try {
                return !Modifier.isStatic(c.getDeclaredMethod(method, types(argumentTypes)).getModifiers());
            } catch (NoSuchMethodException x) {
                return false;
            }
        }

        @Override
        public boolean isWildcard() {
            return "*".equals(method);
        }
    }

    static class StaticMethodSignature extends MethodSignature {
        StaticMethodSignature(String receiverType, String method, String[] argumentTypes) {
            super(receiverType, method, argumentTypes);
        }
        @Override public String toString() {
            return "staticMethod " + signaturePart();
        }
        @Override boolean exists() throws Exception {
            try {
                return Modifier.isStatic(type(receiverType).getDeclaredMethod(method, types(argumentTypes)).getModifiers());
            } catch (NoSuchMethodException x) {
                return false;
            }
        }
    }

    public static final class NewSignature extends Signature  {
        private final String type;
        private final String[] argumentTypes;
        public NewSignature(String type, String[] argumentTypes) {
            this.type = type;
            this.argumentTypes = argumentTypes.clone();
        }
        public NewSignature(Class<?> type, Class<?>... argumentTypes) {
            this(getName(type), argumentTypes(argumentTypes));
        }
        boolean matches(Constructor c) {
            return getName(c.getDeclaringClass()).equals(type) && Arrays.equals(argumentTypes(c.getParameterTypes()), argumentTypes);
        }
        @Override String signaturePart() {
            return joinWithSpaces(new StringBuilder(type), argumentTypes).toString();
        }
        @Override public String toString() {
            return "new " + signaturePart();
        }
        @Override boolean exists() throws Exception {
            try {
                type(type).getDeclaredConstructor(types(argumentTypes));
                return true;
            } catch (NoSuchMethodException x) {
                return false;
            }
        }
    }

    public static class FieldSignature extends Signature {
        final String type, field;
        public FieldSignature(String type, String field) {
            this.type = type;
            this.field = field;
        }
        public FieldSignature(Class<?> type, String field) {
            this(getName(type), field);
        }
        boolean matches(Field f) {
            return is(field, f.getName()) && getName(f.getDeclaringClass()).equals(type);
        }
        @Override String signaturePart() {
            return type + ' ' + field;
        }
        @Override public String toString() {
            return "field " + signaturePart();
        }
        @Override boolean exists() throws Exception {
            try {
                type(type).getField(field);
                return true;
            } catch (NoSuchFieldException x) {
                return false;
            }
        }

        @Override
        public boolean isWildcard() {
            return "*".equals(field);
        }
    }

    static class StaticFieldSignature extends FieldSignature {
        StaticFieldSignature(String type, String field) {
            super(type, field);
        }
        @Override public String toString() {
            return "staticField " + signaturePart();
        }
    }

}
