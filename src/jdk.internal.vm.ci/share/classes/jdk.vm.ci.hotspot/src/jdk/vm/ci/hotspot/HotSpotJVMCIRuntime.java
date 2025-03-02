/*
 * Copyright (c) 2015, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package jdk.vm.ci.hotspot;

import static jdk.vm.ci.common.InitTimer.timer;
import static jdk.vm.ci.hotspot.HotSpotJVMCICompilerFactory.CompilationLevelAdjustment.None;
import static jdk.vm.ci.services.Services.IS_BUILDING_NATIVE_IMAGE;
import static jdk.vm.ci.services.Services.IS_IN_NATIVE_IMAGE;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.module.ModuleDescriptor.Requires;
import java.lang.ref.WeakReference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Predicate;

import jdk.internal.misc.Unsafe;

import jdk.vm.ci.code.Architecture;
import jdk.vm.ci.code.CompilationRequestResult;
import jdk.vm.ci.code.CompiledCode;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.common.InitTimer;
import jdk.vm.ci.common.JVMCIError;
import jdk.vm.ci.common.NativeImageReinitialize;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.UnresolvedJavaType;
import jdk.vm.ci.runtime.JVMCI;
import jdk.vm.ci.runtime.JVMCIBackend;
import jdk.vm.ci.runtime.JVMCICompiler;
import jdk.vm.ci.runtime.JVMCICompilerFactory;
import jdk.vm.ci.runtime.JVMCIRuntime;
import jdk.vm.ci.services.JVMCIServiceLocator;
import jdk.vm.ci.services.Services;

/**
 * HotSpot implementation of a JVMCI runtime.
 */
public final class HotSpotJVMCIRuntime implements JVMCIRuntime {

    /**
     * Singleton instance lazily initialized via double-checked locking.
     */
    @NativeImageReinitialize private static volatile HotSpotJVMCIRuntime instance;

    private HotSpotResolvedObjectTypeImpl javaLangObject;
    private HotSpotResolvedObjectTypeImpl javaLangInvokeMethodHandle;
    private HotSpotResolvedObjectTypeImpl constantCallSiteType;
    private HotSpotResolvedObjectTypeImpl callSiteType;
    private HotSpotResolvedObjectTypeImpl javaLangString;
    private HotSpotResolvedObjectTypeImpl javaLangClass;
    private HotSpotResolvedObjectTypeImpl throwableType;
    private HotSpotResolvedObjectTypeImpl serializableType;
    private HotSpotResolvedObjectTypeImpl cloneableType;
    private HotSpotResolvedObjectTypeImpl enumType;

    HotSpotResolvedObjectTypeImpl getJavaLangObject() {
        if (javaLangObject == null) {
            javaLangObject = (HotSpotResolvedObjectTypeImpl) fromClass(Object.class);
        }
        return javaLangObject;
    }

    HotSpotResolvedObjectTypeImpl getJavaLangString() {
        if (javaLangString == null) {
            javaLangString = (HotSpotResolvedObjectTypeImpl) fromClass(String.class);
        }
        return javaLangString;
    }

    HotSpotResolvedObjectTypeImpl getJavaLangClass() {
        if (javaLangClass == null) {
            javaLangClass = (HotSpotResolvedObjectTypeImpl) fromClass(Class.class);
        }
        return javaLangClass;
    }

    HotSpotResolvedObjectTypeImpl getJavaLangCloneable() {
        if (cloneableType == null) {
            cloneableType = (HotSpotResolvedObjectTypeImpl) fromClass(Cloneable.class);
        }
        return cloneableType;
    }

    HotSpotResolvedObjectTypeImpl getJavaLangSerializable() {
        if (serializableType == null) {
            serializableType = (HotSpotResolvedObjectTypeImpl) fromClass(Serializable.class);
        }
        return serializableType;
    }

    HotSpotResolvedObjectTypeImpl getJavaLangThrowable() {
        if (throwableType == null) {
            throwableType = (HotSpotResolvedObjectTypeImpl) fromClass(Throwable.class);
        }
        return throwableType;
    }

    HotSpotResolvedObjectTypeImpl getJavaLangEnum() {
        if (enumType == null) {
            enumType = (HotSpotResolvedObjectTypeImpl) fromClass(Enum.class);
        }
        return enumType;
    }

    HotSpotResolvedObjectTypeImpl getConstantCallSite() {
        if (constantCallSiteType == null) {
            constantCallSiteType = (HotSpotResolvedObjectTypeImpl) fromClass(ConstantCallSite.class);
        }
        return constantCallSiteType;
    }

    HotSpotResolvedObjectTypeImpl getCallSite() {
        if (callSiteType == null) {
            callSiteType = (HotSpotResolvedObjectTypeImpl) fromClass(CallSite.class);
        }
        return callSiteType;
    }

    HotSpotResolvedObjectType getMethodHandleClass() {
        if (javaLangInvokeMethodHandle == null) {
            javaLangInvokeMethodHandle = (HotSpotResolvedObjectTypeImpl) fromClass(MethodHandle.class);
        }
        return javaLangInvokeMethodHandle;
    }

    /**
     * Gets the singleton {@link HotSpotJVMCIRuntime} object.
     */
    @VMEntryPoint
    @SuppressWarnings("try")
    public static HotSpotJVMCIRuntime runtime() {
        HotSpotJVMCIRuntime result = instance;
        if (result == null) {
            // Synchronize on JVMCI.class to avoid deadlock
            // between the two JVMCI initialization paths:
            // HotSpotJVMCIRuntime.runtime() and JVMCI.getRuntime().
            synchronized (JVMCI.class) {
                result = instance;
                if (result == null) {
                    try (InitTimer t = timer("HotSpotJVMCIRuntime.<init>")) {
                        instance = result = new HotSpotJVMCIRuntime();

                        // Can only do eager initialization of the JVMCI compiler
                        // once the singleton instance is available.
                        if (result.config.getFlag("EagerJVMCI", Boolean.class)) {
                            result.getCompiler();
                        }
                    }
                    // Ensures JVMCIRuntime::_HotSpotJVMCIRuntime_instance is
                    // initialized.
                    JVMCI.getRuntime();
                }
                // Make sure all the primitive box caches are populated (required to properly materialize boxed primitives
                // during deoptimization).
                Object[] boxCaches = { Boolean.valueOf(false), Byte.valueOf((byte)0), Short.valueOf((short) 0), Character.valueOf((char) 0), Integer.valueOf(0), Long.valueOf(0) };
            }
        }
        return result;
    }

    @VMEntryPoint
    static Throwable decodeThrowable(String encodedThrowable) throws Throwable {
        return TranslatedException.decodeThrowable(encodedThrowable);
    }

    @VMEntryPoint
    static String encodeThrowable(Throwable throwable) throws Throwable {
        return TranslatedException.encodeThrowable(throwable);
    }

    @VMEntryPoint
    static String callToString(Object o) {
        return o.toString();
    }

    /**
     * A list of all supported JVMCI options.
     */
    public enum Option {
        // @formatter:off
        Compiler(String.class, null, "Selects the system compiler. This must match the getCompilerName() value returned " +
                "by a jdk.vm.ci.runtime.JVMCICompilerFactory provider. " +
                "An empty string or the value \"null\" selects a compiler " +
                "that will raise an exception upon receiving a compilation request."),
        // Note: The following one is not used (see InitTimer.ENABLED). It is added here
        // so that -XX:+JVMCIPrintProperties shows the option.
        InitTimer(Boolean.class, false, "Specifies if initialization timing is enabled."),
        PrintConfig(Boolean.class, false, "Prints VM configuration available via JVMCI."),
        AuditHandles(Boolean.class, false, "Record stack trace along with scoped foreign object reference wrappers " +
                "to debug issue with a wrapper being used after its scope has closed."),
        TraceMethodDataFilter(String.class, null,
                "Enables tracing of profiling info when read by JVMCI.",
                "Empty value: trace all methods",
                "Non-empty value: trace methods whose fully qualified name contains the value."),
        UseProfilingInformation(Boolean.class, true, "");
        // @formatter:on

        /**
         * The prefix for system properties that are JVMCI options.
         */
        private static final String JVMCI_OPTION_PROPERTY_PREFIX = "jvmci.";

        /**
         * Sentinel for value initialized to {@code null} since {@code null} means uninitialized.
         */
        private static final String NULL_VALUE = "NULL";

        private final Class<?> type;
        @NativeImageReinitialize private Object value;
        private final Object defaultValue;
        private boolean isDefault;
        private final String[] helpLines;

        Option(Class<?> type, Object defaultValue, String... helpLines) {
            assert Character.isUpperCase(name().charAt(0)) : "Option name must start with upper-case letter: " + name();
            this.type = type;
            this.defaultValue = defaultValue;
            this.helpLines = helpLines;
        }

        @SuppressFBWarnings(value = "ES_COMPARING_STRINGS_WITH_EQ", justification = "sentinel must be String since it's a static final in an enum")
        private Object getValue() {
            if (value == null) {
                String propertyValue = Services.getSavedProperty(getPropertyName());
                if (propertyValue == null) {
                    this.value = defaultValue == null ? NULL_VALUE : defaultValue;
                    this.isDefault = true;
                } else {
                    if (type == Boolean.class) {
                        this.value = Boolean.parseBoolean(propertyValue);
                    } else if (type == String.class) {
                        this.value = propertyValue;
                    } else {
                        throw new JVMCIError("Unexpected option type " + type);
                    }
                    this.isDefault = false;
                }
            }
            return value == NULL_VALUE ? null : value;
        }

        /**
         * Gets the name of system property from which this option gets its value.
         */
        public String getPropertyName() {
            return JVMCI_OPTION_PROPERTY_PREFIX + name();
        }

        /**
         * Returns the option's value as boolean.
         *
         * @return option's value
         */
        public boolean getBoolean() {
            return (boolean) getValue();
        }

        /**
         * Returns the option's value as String.
         *
         * @return option's value
         */
        public String getString() {
            return (String) getValue();
        }

        private static final int PROPERTY_LINE_WIDTH = 80;
        private static final int PROPERTY_HELP_INDENT = 10;

        /**
         * Prints a description of the properties used to configure shared JVMCI code.
         *
         * @param out stream to print to
         */
        public static void printProperties(PrintStream out) {
            out.println("[JVMCI properties]");
            Option[] values = values();
            for (Option option : values) {
                Object value = option.getValue();
                if (value instanceof String) {
                    value = '"' + String.valueOf(value) + '"';
                }

                String name = option.getPropertyName();
                String assign = option.isDefault ? "=" : ":=";
                String typeName = option.type.getSimpleName();
                String linePrefix = String.format("%s %s %s ", name, assign, value);
                int typeStartPos = PROPERTY_LINE_WIDTH - typeName.length();
                int linePad = typeStartPos - linePrefix.length();
                if (linePad > 0) {
                    out.printf("%s%-" + linePad + "s[%s]%n", linePrefix, "", typeName);
                } else {
                    out.printf("%s[%s]%n", linePrefix, typeName);
                }
                for (String line : option.helpLines) {
                    out.printf("%" + PROPERTY_HELP_INDENT + "s%s%n", "", line);
                }
            }
        }
    }

    private static HotSpotJVMCIBackendFactory findFactory(String architecture) {
        Iterable<HotSpotJVMCIBackendFactory> factories = getHotSpotJVMCIBackendFactories();
assert factories != null : "sanity";
        for (HotSpotJVMCIBackendFactory factory : factories) {
            if (factory.getArchitecture().equalsIgnoreCase(architecture)) {
                return factory;
            }
        }

        throw new JVMCIError("No JVMCI runtime available for the %s architecture", architecture);
    }

    private static volatile List<HotSpotJVMCIBackendFactory> cachedHotSpotJVMCIBackendFactories;

    @SuppressFBWarnings(value = "LI_LAZY_INIT_UPDATE_STATIC", justification = "not sure about this")
    private static Iterable<HotSpotJVMCIBackendFactory> getHotSpotJVMCIBackendFactories() {
        if (IS_IN_NATIVE_IMAGE || cachedHotSpotJVMCIBackendFactories != null) {
            return cachedHotSpotJVMCIBackendFactories;
        }
        Iterable<HotSpotJVMCIBackendFactory> result = ServiceLoader.load(HotSpotJVMCIBackendFactory.class, ClassLoader.getSystemClassLoader());
        if (IS_BUILDING_NATIVE_IMAGE) {
            cachedHotSpotJVMCIBackendFactories = new ArrayList<>();
            for (HotSpotJVMCIBackendFactory factory : result) {
                cachedHotSpotJVMCIBackendFactories.add(factory);
            }
        }
        return result;
    }

    /**
     * Gets the kind of a word value on the {@linkplain #getHostJVMCIBackend() host} backend.
     */
    public static JavaKind getHostWordKind() {
        return runtime().getHostJVMCIBackend().getCodeCache().getTarget().wordJavaKind;
    }

    protected final CompilerToVM compilerToVm;

    protected final HotSpotVMConfigStore configStore;
    protected final HotSpotVMConfig config;
    private final JVMCIBackend hostBackend;

    private final JVMCICompilerFactory compilerFactory;
    private final HotSpotJVMCICompilerFactory hsCompilerFactory;
    private volatile JVMCICompiler compiler;
    protected final HotSpotJVMCIReflection reflection;

    @NativeImageReinitialize private volatile boolean creatingCompiler;

    /**
     * Cache for speeding up {@link #fromClass(Class)}.
     */
    @NativeImageReinitialize private volatile ClassValue<WeakReferenceHolder<HotSpotResolvedJavaType>> resolvedJavaType;

    /**
     * To avoid calling ClassValue.remove to refresh the weak reference, which
     * under certain circumstances can lead to an infinite loop, we use a
     * permanent holder with a mutable field that we refresh.
     */
    private static class WeakReferenceHolder<T> {
        private volatile WeakReference<T> ref;
        WeakReferenceHolder(T value) {
            set(value);
        }
        void set(T value) {
            ref = new WeakReference<T>(value);
        }
        T get() {
            return ref.get();
        }
    };

    @NativeImageReinitialize private HashMap<Long, WeakReference<ResolvedJavaType>> resolvedJavaTypes;

    /**
     * Stores the value set by {@link #excludeFromJVMCICompilation(Module...)} so that it can
     * be read from the VM.
     */
    @SuppressWarnings("unused")//
    @NativeImageReinitialize private Module[] excludeFromJVMCICompilation;


    private final Map<Class<? extends Architecture>, JVMCIBackend> backends = new HashMap<>();

    private volatile List<HotSpotVMEventListener> vmEventListeners;

    private Iterable<HotSpotVMEventListener> getVmEventListeners() {
        if (vmEventListeners == null) {
            synchronized (this) {
                if (vmEventListeners == null) {
                    vmEventListeners = JVMCIServiceLocator.getProviders(HotSpotVMEventListener.class);
                }
            }
        }
        return vmEventListeners;
    }

    @SuppressWarnings("try")
    private HotSpotJVMCIRuntime() {
        compilerToVm = new CompilerToVM();

        try (InitTimer t = timer("HotSpotVMConfig<init>")) {
            configStore = new HotSpotVMConfigStore(compilerToVm);
            config = new HotSpotVMConfig(configStore);
        }

        reflection = IS_IN_NATIVE_IMAGE ? new SharedLibraryJVMCIReflection() : new HotSpotJDKReflection();

        PrintStream vmLogStream = null;
        if (IS_IN_NATIVE_IMAGE) {
            // Redirect System.out and System.err to HotSpot's TTY stream
            vmLogStream = new PrintStream(getLogStream());
            System.setOut(vmLogStream);
            System.setErr(vmLogStream);
        }

        String hostArchitecture = config.getHostArchitectureName();

        HotSpotJVMCIBackendFactory factory;
        try (InitTimer t = timer("find factory:", hostArchitecture)) {
            factory = findFactory(hostArchitecture);
        }

        try (InitTimer t = timer("create JVMCI backend:", hostArchitecture)) {
            hostBackend = registerBackend(factory.createJVMCIBackend(this, null));
        }

        compilerFactory = HotSpotJVMCICompilerConfig.getCompilerFactory();
        if (compilerFactory instanceof HotSpotJVMCICompilerFactory) {
            hsCompilerFactory = (HotSpotJVMCICompilerFactory) compilerFactory;
            if (hsCompilerFactory.getCompilationLevelAdjustment() != None) {
                String name = HotSpotJVMCICompilerFactory.class.getName();
                String msg = String.format("%s.getCompilationLevelAdjustment() is no longer supported. " +
                                "Use %s.excludeFromJVMCICompilation() instead.", name, name);
                throw new UnsupportedOperationException(msg);
            }
        } else {
            hsCompilerFactory = null;
        }

        if (config.getFlag("JVMCIPrintProperties", Boolean.class)) {
            if (vmLogStream == null) {
                vmLogStream = new PrintStream(getLogStream());
            }
            Option.printProperties(vmLogStream);
            compilerFactory.printProperties(vmLogStream);
            System.exit(0);
        }

        if (Option.PrintConfig.getBoolean()) {
            configStore.printConfig();
        }
    }

    HotSpotResolvedJavaType createClass(Class<?> javaClass) {
        if (javaClass.isPrimitive()) {
            return HotSpotResolvedPrimitiveType.forKind(JavaKind.fromJavaClass(javaClass));
        }
        if (IS_IN_NATIVE_IMAGE) {
            try {
                return compilerToVm.lookupType(javaClass.getName().replace('.', '/'), null, true);
            } catch (ClassNotFoundException e) {
                throw new JVMCIError(e);
            }
        }
        return compilerToVm.lookupClass(javaClass);
    }

    private HotSpotResolvedJavaType fromClass0(Class<?> javaClass) {
        if (resolvedJavaType == null) {
            synchronized (this) {
                if (resolvedJavaType == null) {
                    resolvedJavaType = new ClassValue<WeakReferenceHolder<HotSpotResolvedJavaType>>() {
                        @Override
                        protected WeakReferenceHolder<HotSpotResolvedJavaType> computeValue(Class<?> type) {
                            return new WeakReferenceHolder<>(createClass(type));
                        }
                    };
                }
            }
        }

        WeakReferenceHolder<HotSpotResolvedJavaType> ref = resolvedJavaType.get(javaClass);
        HotSpotResolvedJavaType javaType = ref.get();
        if (javaType == null) {
            /*
             * If the referent has become null, create a new value and
             * update cached weak reference.
             */
            javaType = createClass(javaClass);
            ref.set(javaType);
        }
        return javaType;
    }

    /**
     * Gets the JVMCI mirror for a {@link Class} object.
     *
     * @return the {@link ResolvedJavaType} corresponding to {@code javaClass}
     */
    HotSpotResolvedJavaType fromClass(Class<?> javaClass) {
        if (javaClass == null) {
            return null;
        }
        return fromClass0(javaClass);
    }

    synchronized HotSpotResolvedObjectTypeImpl fromMetaspace(long klassPointer, String signature) {
        if (resolvedJavaTypes == null) {
            resolvedJavaTypes = new HashMap<>();
        }
        assert klassPointer != 0;
        WeakReference<ResolvedJavaType> klassReference = resolvedJavaTypes.get(klassPointer);
        HotSpotResolvedObjectTypeImpl javaType = null;
        if (klassReference != null) {
            javaType = (HotSpotResolvedObjectTypeImpl) klassReference.get();
        }
        if (javaType == null) {
            javaType = new HotSpotResolvedObjectTypeImpl(klassPointer, signature);
            resolvedJavaTypes.put(klassPointer, new WeakReference<>(javaType));
        }
        return javaType;
    }

    private JVMCIBackend registerBackend(JVMCIBackend backend) {
        Class<? extends Architecture> arch = backend.getCodeCache().getTarget().arch.getClass();
        JVMCIBackend oldValue = backends.put(arch, backend);
        assert oldValue == null : "cannot overwrite existing backend for architecture " + arch.getSimpleName();
        return backend;
    }

    public HotSpotVMConfigStore getConfigStore() {
        return configStore;
    }

    public HotSpotVMConfig getConfig() {
        return config;
    }

    public CompilerToVM getCompilerToVM() {
        return compilerToVm;
    }

    HotSpotJVMCIReflection getReflection() {
        return reflection;
    }

    /**
     * Gets a predicate that determines if a given type can be considered trusted for the purpose of
     * intrinsifying methods it declares.
     *
     * @param compilerLeafClasses classes in the leaves of the module graph comprising the JVMCI
     *            compiler.
     */
    public Predicate<ResolvedJavaType> getIntrinsificationTrustPredicate(Class<?>... compilerLeafClasses) {
        return new Predicate<ResolvedJavaType>() {
            @Override
            public boolean test(ResolvedJavaType type) {
                if (type instanceof HotSpotResolvedObjectTypeImpl) {
                    HotSpotResolvedObjectTypeImpl hsType = (HotSpotResolvedObjectTypeImpl) type;
                    return compilerToVm.isTrustedForIntrinsics(hsType);
                } else {
                    return false;
                }
            }
        };
    }

    /**
     * Get the {@link Class} corresponding to {@code type}.
     *
     * @param type the type for which a {@link Class} is requested
     * @return the original Java class corresponding to {@code type} or {@code null} if this runtime
     *         does not support mapping {@link ResolvedJavaType} instances to {@link Class}
     *         instances
     */
    public Class<?> getMirror(ResolvedJavaType type) {
        if (type instanceof HotSpotResolvedJavaType && reflection instanceof HotSpotJDKReflection) {
            return ((HotSpotJDKReflection) reflection).getMirror((HotSpotResolvedJavaType) type);
        }
        return null;
    }

    @Override
    public JVMCICompiler getCompiler() {
        if (compiler == null) {
            synchronized (this) {
                if (compiler == null) {
                    assert !creatingCompiler : "recursive compiler creation";
                    creatingCompiler = true;
                    compiler = compilerFactory.createCompiler(this);
                    creatingCompiler = false;
                }
            }
        }
        return compiler;
    }

    /**
     * Converts a name to a Java type. This method attempts to resolve {@code name} to a
     * {@link ResolvedJavaType}.
     *
     * @param name a well formed Java type in {@linkplain JavaType#getName() internal} format
     * @param accessingType the context of resolution which must be non-null
     * @param resolve specifies whether resolution failure results in an unresolved type being
     *            return or a {@link LinkageError} being thrown
     * @return a Java type for {@code name} which is guaranteed to be of type
     *         {@link ResolvedJavaType} if {@code resolve == true}
     * @throws LinkageError if {@code resolve == true} and the resolution failed
     * @throws NullPointerException if {@code accessingClass} is {@code null}
     */
    public JavaType lookupType(String name, HotSpotResolvedObjectType accessingType, boolean resolve) {
        Objects.requireNonNull(accessingType, "cannot resolve type without an accessing class");
        return lookupTypeInternal(name, accessingType, resolve);
    }

    JavaType lookupTypeInternal(String name, HotSpotResolvedObjectType accessingType, boolean resolve) {
        // If the name represents a primitive type we can short-circuit the lookup.
        if (name.length() == 1) {
            JavaKind kind = JavaKind.fromPrimitiveOrVoidTypeChar(name.charAt(0));
            return HotSpotResolvedPrimitiveType.forKind(kind);
        }

        // Resolve non-primitive types in the VM.
        HotSpotResolvedObjectTypeImpl hsAccessingType = (HotSpotResolvedObjectTypeImpl) accessingType;
        try {
            final HotSpotResolvedJavaType klass = compilerToVm.lookupType(name, hsAccessingType, resolve);

            if (klass == null) {
                assert resolve == false : name;
                return UnresolvedJavaType.create(name);
            }
            return klass;
        } catch (ClassNotFoundException e) {
            throw (NoClassDefFoundError) new NoClassDefFoundError().initCause(e);
        }
    }

    @Override
    public JVMCIBackend getHostJVMCIBackend() {
        return hostBackend;
    }

    @Override
    public <T extends Architecture> JVMCIBackend getJVMCIBackend(Class<T> arch) {
        assert arch != Architecture.class;
        return backends.get(arch);
    }

    public Map<Class<? extends Architecture>, JVMCIBackend> getJVMCIBackends() {
        return Collections.unmodifiableMap(backends);
    }

    @SuppressWarnings("try")
    @VMEntryPoint
    private HotSpotCompilationRequestResult compileMethod(HotSpotResolvedJavaMethod method, int entryBCI, long compileState, int id) {
        HotSpotCompilationRequest request = new HotSpotCompilationRequest(method, entryBCI, compileState, id);
        CompilationRequestResult result = getCompiler().compileMethod(request);
        assert result != null : "compileMethod must always return something";
        HotSpotCompilationRequestResult hsResult;
        if (result instanceof HotSpotCompilationRequestResult) {
            hsResult = (HotSpotCompilationRequestResult) result;
        } else {
            Object failure = result.getFailure();
            if (failure != null) {
                boolean retry = false; // Be conservative with unknown compiler
                hsResult = HotSpotCompilationRequestResult.failure(failure.toString(), retry);
            } else {
                int inlinedBytecodes = -1;
                hsResult = HotSpotCompilationRequestResult.success(inlinedBytecodes);
            }
        }
        return hsResult;
    }

    /**
     * Shuts down the runtime.
     */
    @VMEntryPoint
    private void shutdown() throws Exception {
        // Cleaners are normally only processed when a new Cleaner is
        // instantiated so process all remaining cleaners now.
        Cleaner.clean();

        for (HotSpotVMEventListener vmEventListener : getVmEventListeners()) {
            vmEventListener.notifyShutdown();
        }
    }

    /**
     * Notify on completion of a bootstrap.
     */
    @VMEntryPoint
    private void bootstrapFinished() throws Exception {
        for (HotSpotVMEventListener vmEventListener : getVmEventListeners()) {
            vmEventListener.notifyBootstrapFinished();
        }
    }

    /**
     * Notify on successful install into the CodeCache.
     *
     * @param hotSpotCodeCacheProvider
     * @param installedCode
     * @param compiledCode
     */
    void notifyInstall(HotSpotCodeCacheProvider hotSpotCodeCacheProvider, InstalledCode installedCode, CompiledCode compiledCode) {
        for (HotSpotVMEventListener vmEventListener : getVmEventListeners()) {
            vmEventListener.notifyInstall(hotSpotCodeCacheProvider, installedCode, compiledCode);
        }
    }

    /**
     * Writes {@code length} bytes from {@code bytes} starting at offset {@code offset} to HotSpot's
     * log stream.
     *
     * @param flush specifies if the log stream should be flushed after writing
     * @param canThrow specifies if an error in the {@code bytes}, {@code offset} or {@code length}
     *            arguments should result in an exception or a negative return value. If
     *            {@code false}, this call will not perform any heap allocation
     * @return 0 on success, -1 if {@code bytes == null && !canThrow}, -2 if {@code !canThrow} and
     *         copying would cause access of data outside array bounds
     * @throws NullPointerException if {@code bytes == null}
     * @throws IndexOutOfBoundsException if copying would cause access of data outside array bounds
     */
    public int writeDebugOutput(byte[] bytes, int offset, int length, boolean flush, boolean canThrow) {
        return compilerToVm.writeDebugOutput(bytes, offset, length, flush, canThrow);
    }

    /**
     * Gets an output stream that writes to HotSpot's {@code tty} stream.
     */
    public OutputStream getLogStream() {
        return new OutputStream() {

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (b == null) {
                    throw new NullPointerException();
                } else if (off < 0 || off > b.length || len < 0 || (off + len) > b.length || (off + len) < 0) {
                    throw new IndexOutOfBoundsException();
                } else if (len == 0) {
                    return;
                }
                compilerToVm.writeDebugOutput(b, off, len, false, true);
            }

            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void flush() throws IOException {
                compilerToVm.flushDebugOutput();
            }
        };
    }

    /**
     * Collects the current values of all JVMCI benchmark counters, summed up over all threads.
     */
    public long[] collectCounters() {
        return compilerToVm.collectCounters();
    }

    /**
     * @return the current number of per thread counters. May be set through
     *         {@code -XX:JVMCICompilerSize=} command line option or the
     *         {@link #setCountersSize(int)} call.
     */
    public int getCountersSize() {
        return compilerToVm.getCountersSize();
    }

    /**
     * Enlarge the number of per thread counters available. Requires a safepoint so
     * resizing should be rare to avoid performance effects.
     *
     * @param newSize
     */
    public void setCountersSize(int newSize) {
        compilerToVm.setCountersSize(newSize);
    }

    /**
     * The offset from the origin of an array to the first element.
     *
     * @return the offset in bytes
     */
    public int getArrayBaseOffset(JavaKind kind) {
        switch (kind) {
            case Boolean:
                return compilerToVm.ARRAY_BOOLEAN_BASE_OFFSET;
            case Byte:
                return compilerToVm.ARRAY_BYTE_BASE_OFFSET;
            case Char:
                return compilerToVm.ARRAY_CHAR_BASE_OFFSET;
            case Short:
                return compilerToVm.ARRAY_SHORT_BASE_OFFSET;
            case Int:
                return compilerToVm.ARRAY_INT_BASE_OFFSET;
            case Long:
                return compilerToVm.ARRAY_LONG_BASE_OFFSET;
            case Float:
                return compilerToVm.ARRAY_FLOAT_BASE_OFFSET;
            case Double:
                return compilerToVm.ARRAY_DOUBLE_BASE_OFFSET;
            case Object:
                return compilerToVm.ARRAY_OBJECT_BASE_OFFSET;
            default:
                throw new JVMCIError("%s", kind);
        }

    }

    /**
     * The scale used for the index when accessing elements of an array of this kind.
     *
     * @return the scale in order to convert the index into a byte offset
     */
    public int getArrayIndexScale(JavaKind kind) {
        switch (kind) {
            case Boolean:
                return compilerToVm.ARRAY_BOOLEAN_INDEX_SCALE;
            case Byte:
                return compilerToVm.ARRAY_BYTE_INDEX_SCALE;
            case Char:
                return compilerToVm.ARRAY_CHAR_INDEX_SCALE;
            case Short:
                return compilerToVm.ARRAY_SHORT_INDEX_SCALE;
            case Int:
                return compilerToVm.ARRAY_INT_INDEX_SCALE;
            case Long:
                return compilerToVm.ARRAY_LONG_INDEX_SCALE;
            case Float:
                return compilerToVm.ARRAY_FLOAT_INDEX_SCALE;
            case Double:
                return compilerToVm.ARRAY_DOUBLE_INDEX_SCALE;
            case Object:
                return compilerToVm.ARRAY_OBJECT_INDEX_SCALE;
            default:
                throw new JVMCIError("%s", kind);

        }
    }

    /**
     * Links each native method in {@code clazz} to an implementation in the JVMCI shared library.
     * <p>
     * A use case for this is a JVMCI compiler implementation that offers an API to Java code
     * executing in HotSpot to exercise functionality (mostly) in the JVMCI shared library. For
     * example:
     *
     * <pre>
     * package com.jcompile;
     *
     * import java.lang.reflect.Method;
     *
     * public static class JCompile {
     *     static {
     *         HotSpotJVMCIRuntime.runtime().registerNativeMethods(JCompile.class);
     *     }
     *     public static boolean compile(Method method, String[] options) {
     *         // Convert to simpler data types for passing/serializing across native interface
     *         long metaspaceMethodHandle = getHandle(method);
     *         char[] opts = convertToCharArray(options);
     *         return compile(metaspaceMethodHandle, opts);
     *     }
     *     private static native boolean compile0(long metaspaceMethodHandle, char[] options);
     *
     *     private static long getHandle(Method method) { ... }
     *     private static char[] convertToCharArray(String[] a) { ... }
     * }
     * </pre>
     *
     * The implementation of the native {@code JCompile.compile0} method would be in the JVMCI
     * shared library that contains the bulk of the JVMCI compiler. The {@code JCompile.compile0}
     * implementation will be exported as the following JNI-compatible symbol:
     *
     * <pre>
     * Java_com_jcompile_JCompile_compile0
     * </pre>
     *
     * @see "https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/design.html#resolving_native_method_names"
     * @see "https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/invocation.html#creating_the_vm"
     * @see "https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/invocation.html#invocation_api_functions"
     *
     *
     * @return an array of 4 longs where the first value is the {@code JavaVM*} value representing
     *         the Java VM in the JVMCI shared library, and the remaining values are the first 3
     *         pointers in the Invocation API function table (i.e., {@code JNIInvokeInterface})
     * @throws NullPointerException if {@code clazz == null}
     * @throws UnsupportedOperationException if the JVMCI shared library is not enabled (i.e.
     *             {@code -XX:-UseJVMCINativeLibrary})
     * @throws IllegalStateException if the current execution context is the JVMCI shared library
     * @throws IllegalArgumentException if {@code clazz} is {@link Class#isPrimitive()}
     * @throws UnsatisfiedLinkError if there's a problem linking a native method in {@code clazz}
     *             (no matching JNI symbol or the native method is already linked to a different
     *             address)
     */
    public long[] registerNativeMethods(Class<?> clazz) {
        return compilerToVm.registerNativeMethods(clazz);
    }

    /**
     * Creates or retrieves an object in the peer runtime that mirrors {@code obj}. The types whose
     * objects can be translated are:
     * <ul>
     * <li>{@link HotSpotResolvedJavaMethodImpl},</li>
     * <li>{@link HotSpotResolvedObjectTypeImpl},</li>
     * <li>{@link HotSpotResolvedPrimitiveType},</li>
     * <li>{@link IndirectHotSpotObjectConstantImpl},</li>
     * <li>{@link DirectHotSpotObjectConstantImpl} and</li>
     * <li>{@link HotSpotNmethod}</li>
     * </ul>
     *
     * This mechanism can be used to pass and return values between the HotSpot and JVMCI shared
     * library runtimes. In the receiving runtime, the value can be converted back to an object with
     * {@link #unhand(Class, long)}.
     *
     * @param obj an object for which an equivalent instance in the peer runtime is requested
     * @return a JNI global reference to the mirror of {@code obj} in the peer runtime
     * @throws UnsupportedOperationException if the JVMCI shared library is not enabled (i.e.
     *             {@code -XX:-UseJVMCINativeLibrary})
     * @throws IllegalArgumentException if {@code obj} is not of a translatable type
     *
     * @see "https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/design.html#global_and_local_references"
     */
    public long translate(Object obj) {
        return compilerToVm.translate(obj);
    }

    /**
     * Dereferences and returns the object referred to by the JNI global reference {@code handle}.
     * The global reference is deleted prior to returning. Any further use of {@code handle} is
     * invalid.
     *
     * @param handle a JNI global reference to an object in the current runtime
     * @return the object referred to by {@code handle}
     * @throws UnsupportedOperationException if the JVMCI shared library is not enabled (i.e.
     *             {@code -XX:-UseJVMCINativeLibrary})
     * @throws ClassCastException if the returned object cannot be cast to {@code type}
     *
     * @see "https://docs.oracle.com/javase/8/docs/technotes/guides/jni/spec/design.html#global_and_local_references"
     *
     */
    public <T> T unhand(Class<T> type, long handle) {
        return type.cast(compilerToVm.unhand(handle));
    }

    /**
     * Determines if the current thread is attached to the peer runtime.
     *
     * @throws UnsupportedOperationException if the JVMCI shared library is not enabled (i.e.
     *             {@code -XX:-UseJVMCINativeLibrary})
     * @throws IllegalStateException if the peer runtime has not been initialized
     */
    public boolean isCurrentThreadAttached() {
        return compilerToVm.isCurrentThreadAttached();
    }

    /**
     * Gets the address of the HotSpot {@code JavaThread} C++ object for the current thread. This
     * will return {@code 0} if called from an unattached JVMCI shared library thread.
     */
    public long getCurrentJavaThread() {
        return compilerToVm.getCurrentJavaThread();
    }

    /**
     * Ensures the current thread is attached to the peer runtime.
     *
     * @param asDaemon if the thread is not yet attached, should it be attached as a daemon
     * @return {@code true} if this call attached the current thread, {@code false} if the current
     *         thread was already attached
     * @throws UnsupportedOperationException if the JVMCI shared library is not enabled (i.e.
     *             {@code -XX:-UseJVMCINativeLibrary})
     * @throws IllegalStateException if the peer runtime has not been initialized or there is an
     *             error while trying to attach the thread
     */
    public boolean attachCurrentThread(boolean asDaemon) {
        return compilerToVm.attachCurrentThread(asDaemon);
    }

    /**
     * Detaches the current thread from the peer runtime.
     *
     * @throws UnsupportedOperationException if the JVMCI shared library is not enabled (i.e.
     *             {@code -XX:-UseJVMCINativeLibrary})
     * @throws IllegalStateException if the peer runtime has not been initialized or if the current
     *             thread is not attached or if there is an error while trying to detach the thread
     */
    public void detachCurrentThread() {
        compilerToVm.detachCurrentThread();
    }

    /**
     * Informs HotSpot that no method whose module is in {@code modules} is to be compiled
     * with {@link #compileMethod}.
     *
     * @param modules the set of modules containing JVMCI compiler classes
     */
    public void excludeFromJVMCICompilation(Module...modules) {
        this.excludeFromJVMCICompilation = modules.clone();
    }

    /**
     * Calls {@link System#exit(int)} in HotSpot's runtime.
     */
    public void exitHotSpot(int status) {
        if (!IS_IN_NATIVE_IMAGE) {
            System.exit(status);
        }
        compilerToVm.callSystemExit(status);
    }
}
