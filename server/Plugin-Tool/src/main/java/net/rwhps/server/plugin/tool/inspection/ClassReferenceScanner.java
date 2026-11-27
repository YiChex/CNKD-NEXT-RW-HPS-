package net.rwhps.server.plugin.tool.inspection;

import net.rwhps.server.plugin.tool.model.ClassScanSummary;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClassReferenceScanner {
    private static final Pattern SIGNATURE_CLASS = Pattern.compile("L([^;<]+)");
    private static final String LEGACY_PLUGIN = "net/rwhps/server/plugin/Plugin";

    public void scan(byte[] classBytes, ClassScanSummary summary) {
        if (classBytes.length < 8) {
            throw new IllegalArgumentException("class header is truncated");
        }
        int major = ((classBytes[6] & 0xff) << 8) | (classBytes[7] & 0xff);
        summary.classCount++;
        summary.maxClassMajor = Math.max(summary.maxClassMajor, major);
        new ClassReader(classBytes).accept(new ReferenceVisitor(summary), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static final class ReferenceVisitor extends ClassVisitor {
        private final ClassScanSummary summary;

        private ReferenceVisitor(ClassScanSummary summary) {
            super(Opcodes.ASM9);
            this.summary = summary;
        }

        @Override
        public void visit(
            int version,
            int access,
            String name,
            String signature,
            String superName,
            String[] interfaces
        ) {
            addClass(superName);
            summary.extendsLegacyPlugin |= LEGACY_PLUGIN.equals(superName);
            if (name != null) {
                if (superName != null) summary.superClasses.put(name, superName);
                Set<String> classInterfaces = new java.util.TreeSet<>();
                if (interfaces != null) classInterfaces.addAll(java.util.Arrays.asList(interfaces));
                summary.interfaces.put(name, classInterfaces);
            }
            if (interfaces != null) for (String interfaceName : interfaces) addClass(interfaceName);
            addSignature(signature);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            addDescriptor(descriptor);
            return annotationVisitor();
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            addDescriptor(descriptor);
            addSignature(signature);
            addValue(value);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
            int access,
            String name,
            String descriptor,
            String signature,
            String[] exceptions
        ) {
            if (summary.declaredMethods.size() < 2_048) summary.declaredMethods.add(name + descriptor);
            addDescriptor(descriptor);
            addSignature(signature);
            if (exceptions != null) for (String exception : exceptions) addClass(exception);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor, boolean visible) {
                    addDescriptor(annotationDescriptor);
                    return annotationVisitor();
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    addClass(type);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String memberName, String memberDescriptor) {
                    addMember(owner, memberName, memberDescriptor);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String memberName, String memberDescriptor, boolean isInterface) {
                    addMember(owner, memberName, memberDescriptor);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String dynamicDescriptor, Handle bootstrapMethodHandle, Object... arguments) {
                    addDescriptor(dynamicDescriptor);
                    addHandle(bootstrapMethodHandle);
                    if (arguments != null) for (Object argument : arguments) addValue(argument);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    addValue(value);
                }

                @Override
                public void visitMultiANewArrayInsn(String arrayDescriptor, int dimensions) {
                    addDescriptor(arrayDescriptor);
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) {
                    addClass(type);
                }
            };
        }

        private AnnotationVisitor annotationVisitor() {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    addValue(value);
                }

                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    addDescriptor(descriptor);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    addDescriptor(descriptor);
                    return annotationVisitor();
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return annotationVisitor();
                }
            };
        }

        private void addMember(String owner, String name, String descriptor) {
            addClass(owner);
            addDescriptor(descriptor);
            if (summary.referencedMembers.size() < 4_096) summary.referencedMembers.add(owner + "#" + name + descriptor);
        }

        private void addHandle(Handle handle) {
            if (handle != null) addMember(handle.getOwner(), handle.getName(), handle.getDesc());
        }

        private void addValue(Object value) {
            if (value instanceof Type) {
                addType((Type) value);
            } else if (value instanceof Handle) {
                addHandle((Handle) value);
            } else if (value instanceof ConstantDynamic) {
                ConstantDynamic dynamic = (ConstantDynamic) value;
                addDescriptor(dynamic.getDescriptor());
                addHandle(dynamic.getBootstrapMethod());
                for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                    addValue(dynamic.getBootstrapMethodArgument(index));
                }
            } else if (value instanceof String) {
                String text = (String) value;
                if (text.startsWith("net.rwhps.server.") || text.startsWith("com.corrodinggames.")) {
                    String className = text.replace('.', '/');
                    if (summary.reflectiveClassNames.size() < 256) summary.reflectiveClassNames.add(className);
                    addClass(className);
                }
            }
        }

        private void addDescriptor(String descriptor) {
            if (descriptor == null || descriptor.isEmpty()) return;
            try {
                if (descriptor.charAt(0) == '(') {
                    for (Type argument : Type.getArgumentTypes(descriptor)) addType(argument);
                    addType(Type.getReturnType(descriptor));
                } else {
                    addType(Type.getType(descriptor));
                }
            } catch (IllegalArgumentException ignored) {
                addSignature(descriptor);
            }
        }

        private void addType(Type type) {
            if (type == null) return;
            if (type.getSort() == Type.ARRAY) addType(type.getElementType());
            else if (type.getSort() == Type.OBJECT) addClass(type.getInternalName());
            else if (type.getSort() == Type.METHOD) {
                for (Type argument : type.getArgumentTypes()) addType(argument);
                addType(type.getReturnType());
            }
        }

        private void addSignature(String signature) {
            if (signature == null) return;
            Matcher matcher = SIGNATURE_CLASS.matcher(signature);
            while (matcher.find()) addClass(matcher.group(1));
        }

        private void addClass(String internalName) {
            if (internalName == null || internalName.isEmpty()) return;
            String normalized = internalName;
            while (normalized.startsWith("[")) normalized = normalized.substring(1);
            if (normalized.startsWith("L") && normalized.endsWith(";")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            if (!normalized.isEmpty() && summary.referencedClasses.size() < 4_096) summary.referencedClasses.add(normalized);
        }
    }
}
