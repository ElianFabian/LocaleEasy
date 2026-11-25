package com.elianfabian.localeeasy.gradle;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.gradle.api.GradleException;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public abstract class AppCompatContentInstrumentation
        implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    static final String RUNTIME_OWNER = "com/elianfabian/locale_easy/AppCompatWebViewContent";

    @Override
    public boolean isInstrumentable(ClassData data) {
        return data.getClassName().equals("androidx.appcompat.app.AppCompatDelegateImpl");
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            private boolean instrumented;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("setContentView") || !descriptor.equals("(I)V")) return method;
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                            boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals("android/view/LayoutInflater")
                                && name.equals("inflate")
                                && descriptor.equals("(ILandroid/view/ViewGroup;)Landroid/view/View;")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, RUNTIME_OWNER, "inflateAppCompatContent",
                                    "(Landroid/view/LayoutInflater;ILandroid/view/ViewGroup;)Landroid/view/View;",
                                    false);
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
                    throw new GradleException("LocaleEasy could not locate AppCompat's content inflation. "
                            + "Use a supported AppCompat version or leave webViewContentViewActivities empty.");
                }
                super.visitEnd();
            }
        };
    }
}
