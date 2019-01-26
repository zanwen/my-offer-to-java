package top.zhenganwen.jvmtest.exectmpcode;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;

/**
 * 执行临时代码
 *      获取临时代码二进制字节流
 *      通过ClassModifier改变符号引用
 *      通过HotSwapClassLoader将改变后的字节码加载并通过反射执行临时代码的main方法
 */
public class JavaClassExecuter {

    public static String execute(byte[] classByte){
        HackSystem.clearBuffer();
        ClassModifier classModifier = new ClassModifier(classByte);
        byte[] bytes = new byte[0];
        try {
            bytes = classModifier.modifyUTF8Constant("java/lang/System", "top/zhenganwen/jvmtest.exectmpcode/HackSystem");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        //每次执行临时代码都通过一个新的类加载器加载
        HotSwapClassLoader hotSwapClassLoader = new HotSwapClassLoader();
        Class clazz = hotSwapClassLoader.loadByte(bytes);
        try {
            // main(String... args)
            Method method = clazz.getMethod("main", new Class[]{String[].class});
            // obj -> 方法接收者（main方法本地变量表不需要this）     args - > 方法参数
            method.invoke(null, new String[]{null});
        } catch (Exception e) {
            e.printStackTrace();
        }

        return HackSystem.getBufferString();
    }

    public static void main(String[] args) throws IOException {
        FileInputStream in = new FileInputStream("C:\\Users\\zaw\\Desktop\\HelloWorld.class");
        byte[] bytes = new byte[in.available()];
        in.read(bytes);
        System.out.println(execute(bytes));
    }
}
