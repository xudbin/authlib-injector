package moe.yushi.authlibinjector.transform;

import static org.objectweb.asm.Opcodes.ASM6;
import java.util.Optional;
import java.util.function.Function;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import moe.yushi.authlibinjector.util.Logging;

public class LdcTransformUnit implements TransformUnit {

	private Function<String, Optional<String>> ldcMapper;

	public LdcTransformUnit(Function<String, Optional<String>> ldcMapper) {
		this.ldcMapper = ldcMapper;
	}

	@Override
	public Optional<ClassVisitor> transform(String className, ClassVisitor writer, Runnable modifiedCallback) {
		return Optional.of(new ClassVisitor(ASM6, writer) {

			@Override
			public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
				return new MethodVisitor(ASM6, super.visitMethod(access, name, desc, signature, exceptions)) {

					@Override
					public void visitLdcInsn(Object cst) {
						if (cst instanceof String) {
							Optional<String> transformed = ldcMapper.apply((String) cst);
							if (transformed.isPresent() && !transformed.get().equals(cst)) {
								modifiedCallback.run();
								Logging.TRANSFORM.info("transform [" + cst + "] to [" + transformed.get() + "]");
								super.visitLdcInsn(transformed.get());
							} else {
								super.visitLdcInsn(cst);
							}
						} else {
							super.visitLdcInsn(cst);
						}
					}
				};
			}
		});
	}
}
