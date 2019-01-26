package top.zhenganwen.jvmtest.exectmpcode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * 用来替换java.lang.System将临时代码的输出劫持并返回客户端
 * 成员要与java.lang.System中的保持一致，以保证临时代码在服
 * 务端的正常执行
 * <p>
 * 只是将System.out和System.err劫持，其它的委托给System
 */
public class HackSystem {

    public static final InputStream in = System.in;

    public static final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    /**
     * 将临时代码中System.out.print的输出劫持放到buffer中
     */
    public static final PrintStream out = new PrintStream(buffer);
    public static final PrintStream err = out;

    public static String getBufferString() {
        return new String(buffer.toByteArray());
    }

    public static void clearBuffer() {
        buffer.reset();
    }

    public static long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    public static void arraycopy(Object src, int srcPos,
                                 Object dest, int destPos,
                                 int length) {
        System.arraycopy(src, srcPos, dest, destPos, length);
    }

    /**
     * 等等所有System的公开API都需要这样委托一下
     */
}
