package top.zhenganwen.jvmtest.exectmpcode;

/**
 * HotSwapClassLoader class
 *
 * 1、通过一串Class文件的字节流加载该类并返回该类的Class对象 -> loadByte
 * 2、于在构造方法中将加载HotSwapClassLoader的类加载器设置为它的父加载器，
 *      因此HotSwapClassLoader符合服务端的双亲委派机制，即要执行的字节码
 *      文件能够使用服务端的API
 *
 * @author zhenganwen,blog:zhenganwen.top
 * @date 2019/1/25
 */
public class HotSwapClassLoader extends ClassLoader{

    public HotSwapClassLoader() {
        super(HotSwapClassLoader.class.getClassLoader());
    }

    public Class loadByte(byte[] classByte) {
        return defineClass(null, classByte, 0, classByte.length);
    }
}
