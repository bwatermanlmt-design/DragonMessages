import org.objectweb.asm.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * Neutralizes Fossify Commons' anti-fork nag: every method named
 * showModdedAppWarning or FakeVersionCheck in the library gets its body
 * replaced with an immediate return, so the "fake version" dialog can
 * never be shown on either the view-based or the Compose path.
 *
 * Nothing else is touched: all classes, resources, copyright notices
 * and license headers in the AAR are preserved byte-for-byte, and the
 * original artifact (org.fossify:commons:7.1.3) is left untouched.
 * The patched artifact is published as 7.1.3-dragon1 so the change
 * is explicit in the dependency graph, never silent.
 */
public class PatchCommons {
    public static void main(String[] args) throws Exception {
        Path in = Path.of(args[0]), out = Path.of(args[1]);
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(in));
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(out))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = zin.readAllBytes();
                if (e.getName().endsWith(".class")) {
                    data = patchClass(data);
                }
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setMethod(e.getMethod());
                if (e.getMethod() == ZipEntry.STORED) {
                    ne.setSize(data.length);
                    ne.setCompressedSize(data.length);
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    ne.setCrc(crc.getValue());
                }
                zout.putNextEntry(ne);
                zout.write(data);
                zout.closeEntry();
            }
        }
    }

    static byte[] patchClass(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                              String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                if (!name.equals("showModdedAppWarning") && !name.equals("FakeVersionCheck")) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        mv.visitInsn(Opcodes.RETURN);
                    }

                    // Swallow every instruction the original body would emit.
                    @Override public void visitInsn(int o) {}
                    @Override public void visitIntInsn(int o, int v) {}
                    @Override public void visitVarInsn(int o, int v) {}
                    @Override public void visitTypeInsn(int o, String t) {}
                    @Override public void visitFieldInsn(int o, String ow, String n, String d) {}
                    @Override public void visitMethodInsn(int o, String ow, String n, String d, boolean itf) {}
                    @Override public void visitInvokeDynamicInsn(String n, String d, Handle h, Object... b) {}
                    @Override public void visitJumpInsn(int o, Label l) {}
                    @Override public void visitLabel(Label l) {}
                    @Override public void visitLdcInsn(Object v) {}
                    @Override public void visitIincInsn(int v, int i) {}
                    @Override public void visitTableSwitchInsn(int a, int b, Label d, Label... l) {}
                    @Override public void visitLookupSwitchInsn(Label d, int[] k, Label[] l) {}
                    @Override public void visitMultiANewArrayInsn(String d, int n) {}
                    @Override public void visitFrame(int t, int nL, Object[] l, int nS, Object[] s) {}
                    @Override public void visitLineNumber(int l, Label s) {}
                    @Override public void visitLocalVariable(String n, String d, String s, Label st, Label e, int i) {}

                    @Override public void visitMaxs(int a, int b) { super.visitMaxs(a, b); }
                    @Override public void visitEnd() { super.visitEnd(); }
                };
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }
}
