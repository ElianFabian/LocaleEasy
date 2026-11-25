package com.elianfabian.localeeasy.gradle;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.gradle.api.GradleException;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public abstract class FragmentContentInstrumentation
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    private static final String INFLATER = "android/view/LayoutInflater";
    private static final String VIEW = "Landroid/view/View;";
    private static final String CONTAINER = "Landroid/view/ViewGroup;";
    private static final String INFLATE = "(I" + CONTAINER + "Z)" + VIEW;

    @Override
    public boolean isInstrumentable(ClassData data) {
        return data.getClassName().equals("androidx.fragment.app.Fragment");
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            private boolean instrumented;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("onCreateView")
                        || !descriptor.equals("(L" + INFLATER + ";" + CONTAINER + "Landroid/os/Bundle;)" + VIEW)) {
                    return method;
                }
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(INFLATER)
                                && name.equals("inflate") && descriptor.equals(INFLATE)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, InstrumentClasses.WEB_VIEW_INFLATION,
                                    "inflate", "(L" + INFLATER + ";" + INFLATE.substring(1), false);
                            instrumented = true;
                        } else {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        }
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (!instrumented) {
                    throw new GradleException("LocaleEasy could not locate AndroidX Fragment's content inflation. "
                            + "Use a supported Fragment version or disable instrumentWebViewCreation.");
                }
                super.visitEnd();
            }
        };
    }
}
