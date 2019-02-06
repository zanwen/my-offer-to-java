---
title: 从CPU Cache出发彻底弄懂volatile/synchronized/cas
date: 2019-02-03 21:41:15
img: https://images.pexels.com/photos/1845562/pexels-photo-1845562.jpeg?auto=compress&cs=tinysrgb&dpr=1&w=500
top: true
categories: 后端
tags: 并发编程
---

# 变量可见吗

## 共享变量可见吗

首先引入一段代码指出Java内存模型存在的问题：启动两个线程`t1，t2`访问共享变量`sharedVariable`，`t2`线程逐渐将`sharedVariable`自增到`MAX`，每自增一次就休眠`500ms`放弃CPU执行权，期望此间另外一个线程`t1`能够在第`7-12`行轮询过程中发现到`sharedVariable`的改变并将其打印

```java
private static int sharedVariable = 0;
private static final int MAX = 10;

public static void main(String[] args) {
    new Thread(() -> {
        int oldValue = sharedVariable;
        while (sharedVariable < MAX) {
            if (sharedVariable != oldValue) {
                System.out.println(Thread.currentThread().getName() + " watched the change : " + oldValue + "->" + sharedVariable);
                oldValue = sharedVariable;
            }
        }
    }， "t1").start();

    new Thread(() -> {
        int oldValue = sharedVariable;
        while (sharedVariable < MAX) {
            System.out.println(Thread.currentThread().getName() + " do the change : " + sharedVariable + "->" + (++oldValue));
            sharedVariable = oldValue;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }， "t2").start();

}
```

但上述程序的实际运行结果如下：

```java
t2 do the change : 0->1
t1 watched the change : 0->1
t2 do the change : 1->2
t2 do the change : 2->3
t2 do the change : 3->4
t2 do the change : 4->5
t2 do the change : 5->6
t2 do the change : 6->7
t2 do the change : 7->8
t2 do the change : 8->9
t2 do the change : 9->10
```

## volatile能够保证可见性

可以发现`t1`线程几乎察觉不到`t2`每次对共享变量`sharedVariable`所做的修改，这是为什么呢？也许会有人告诉你给`sharedVariable`加个`volatile`修饰就好了，确实，加了`volatile`之后的输出达到我们的预期了：

```java
t2 do the change : 0->1
t1 watched the change : 0->1
t2 do the change : 1->2
t1 watched the change : 1->2
t2 do the change : 2->3
t1 watched the change : 2->3
t2 do the change : 3->4
t1 watched the change : 3->4
t2 do the change : 4->5
t1 watched the change : 4->5
t2 do the change : 5->6
t1 watched the change : 5->6
t2 do the change : 6->7
t1 watched the change : 6->7
t2 do the change : 7->8
t1 watched the change : 7->8
t2 do the change : 8->9
t1 watched the change : 8->9
t2 do the change : 9->10
```

这也比较好理解，官方说`volatile`能够保证共享变量在线程之间的可见性。

## synchronized能保证可见性吗？

但是，也可能会有人跟你说，你使用`synchronized + wait/notify`模型就好了：将所有对共享变量操作都放入同步代码块，然后使用`wait/notify`协调共享变量的修改和读取

```java
private static int sharedVariable = 0;
private static final int MAX = 10;
private static Object lock = new Object();
private static boolean changed = false;

public static void main(String[] args) {
    new Thread(() -> {
        synchronized (lock) {
            int oldValue = sharedVariable;
            while (sharedVariable < MAX) {
                while (!changed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                System.out.println(Thread.currentThread().getName() +
                                   " watched the change : " + oldValue + "->" + sharedVariable);
                oldValue = sharedVariable;
                changed = false;
                lock.notifyAll();
            }
        }
    }， "t1").start();

    new Thread(() -> {
        synchronized (lock) {
            int oldValue = sharedVariable;
            while (sharedVariable < MAX) {
                while (changed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                System.out.println(Thread.currentThread().getName() +
                                   " do the change : " + sharedVariable + "->" + (++oldValue));
                sharedVariable = oldValue;
                changed = true;
                lock.notifyAll();
            }
        }
    }， "t2").start();

}
```

你会发现这种方式即使没有给`sharedVariable`、`changed`加`volatile`，但他们在`t1`和`t2`之间似乎也是可见的：

```java
t2 do the change : 0->1
t1 watched the change : 0->1
t2 do the change : 0->2
t1 watched the change : 0->2
t2 do the change : 0->3
t1 watched the change : 0->3
t2 do the change : 0->4
t1 watched the change : 0->4
t2 do the change : 0->5
t1 watched the change : 0->5
t2 do the change : 0->6
t1 watched the change : 0->6
t2 do the change : 0->7
t1 watched the change : 0->7
t2 do the change : 0->8
t1 watched the change : 0->8
t2 do the change : 0->9
t1 watched the change : 0->9
t2 do the change : 0->10
t1 watched the change : 0->10
```

## CAS能保证可见性吗？

将`sharedVariable`的类型改为`AtomicInteger`，`t2`线程使用`AtomicInteger`提供的`getAndSet`CAS更新该变量，你会发现这样这能做到可见性。

```java
private static AtomicInteger sharedVariable = new AtomicInteger(0);
private static final int MAX = 10;

public static void main(String[] args) {
    new Thread(() -> {
        int oldValue = sharedVariable.get();
        while (sharedVariable.get() < MAX) {
            if (sharedVariable.get() != oldValue) {
                System.out.println(Thread.currentThread().getName() + " watched the change : " + oldValue + "->" + sharedVariable);
                oldValue = sharedVariable.get();
            }
        }
    }， "t1").start();

    new Thread(() -> {
        int oldValue = sharedVariable.get();
        while (sharedVariable.get() < MAX) {
            System.out.println(Thread.currentThread().getName() + " do the change : " + sharedVariable + "->" + (++oldValue));
            sharedVariable.set(oldValue);
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }， "t2").start();

}
```

为什么`synchronized`和`CAS`也能做到可见性呢？其实这是因为`synchronized`的**锁释放-获取**和**CAS修改-读取**都有着和`volatile`域的**写-读**有相同的语义。既然这么神奇，那就让我们一起去Java内存模型、`synchronized/volatile/CAS`的底层实现一探究竟吧！

# CPU Cache

要理解变量在线程间的可见性，首先我们要了解CPU的读写模型，虽然可能有些无聊，但这对并发编程的理解有很大的帮助！

## 主存RAM & 高速缓存Cache

在计算机技术发展过程中，**主存储器存取速度一直比CPU操作速度慢得多**，这使得CPU的高速处理能力不能充分发挥，整个计算机系统的工作效率受到影响，因此现代处理器一般都引入了高速缓冲存储器（简称高速缓存）。

高速缓存的存取速度能与CPU相匹配，但因造价高昂因此容量较主存小很多。据**程序局部性原理**，当CPU试图访问主存中的某一单元（一个存储单元对应一个字节）时，其邻近的那些单元在随后将被用到的可能性很大。因而，当CPU存取主存单元时，计算机硬件就自动地将包括该单元在内的那一组单元（称之为内存块`block`，通常是连续的64个字节）内容调入高速缓存，CPU即将存取的主存单元很可能就在刚刚调入到高速缓存的那一组单元内。于是，CPU就可以直接对高速缓存进行存取。在整个处理过程中，如果CPU绝大多数存取主存的操作能被存取高速缓存所代替，计算机系统处理速度就能显著提高。

## Cache相关术语

以下术语在初次接触时可能会一知半解，but take it easy，后文的讲解将逐步揭开你心中的谜团。

### Cache Line & Slot & Hot Data

前文说道，CPU请求访问主存中的某一存储单元时，会将包括该存储单元在内的那一组单元都调入高速缓存。这一组单元（我们通常称之为内存块block）将会被存放在高速缓存的缓存行中（cache line，也叫slot）。高速缓存会将其存储单元均分成若干等份，每一等份就是一个缓存行，如今主流CPU的缓存行一般都是64个字节（也就是说如果高速缓存大小为512字节，那么就对应有8个缓存行）。

另外，被缓存行缓存的数据称之为热点数据（hot data）。

### Cache Hit

当CPU通过寄存器中存储的数据地址请求访问数据时（包括读操作和写操作），首先会在Cache中查找，如果找到了则直接返回Cache中存储的数据，这称为缓存命中（cache hit），根据操作类型又可分为读缓存命中和写缓存命中。

### Cache Miss & Hit Latency

与cache hit相对应，如果没有找到那么将会通过系统总线（System Bus）到主存中找，这称为缓存缺失（cache miss）。如果发生了缓存缺失，那么原本应该直接存取主存的操作因为Cache的存在，浪费了一些时间，这称为命中延迟（hit latency）。确切地说，命中延迟是指判断Cache中是否缓存了目标数据所花的时间。

### Cache分级

如果打开你的任务管理器查看CPU性能，你可能会发现笔者的高速缓存有三块区域：L1（一级缓存，128KB）、L2（二级缓存，512KB）、L3（共享缓存3.0MB）：

![image](https://wx4.sinaimg.cn/mw690/006zweohgy1fzrugo3cc7j30o00emq4l.jpg)

起初Cache的实现只有一级缓存L1，后来随着科技的发展，一方面主存的增大导致需要缓存的热点数据变多，单纯的增大L1的容量所获取的性价比会很低；另一方面，L1的存取速度和主存的存取速度进一步拉大，需要一个基于两者存取速度之间的缓存做缓冲。基于以上两点考虑，引入了二级缓存L2，它的存取速度介于L1和主存之间且存取容量在L1的基础上进行了扩容。

上述的L1和L2一般都是处理器私有的，也就是说每个CPU核心都有它自己的L1和L2并且是不与其他核心共享的。这时，为了能有一块所有核心都共享的缓存区域，也为了防止L1和L2都发生缓存缺失而进一步提高缓存命中率，加入了L3。可以猜到L3比L1、L2的存取速度都慢，但容量较大。

###  Cache替换算法 & Cache Line Conflict

为了保证CPU访问时有较高的命中率，Cache中的内容应该按一定的算法替换。一种较常用的算法是“最近最少使用算法”（LRU算法），它是将最近一段时间内最少被访问过的行淘汰出局。因此需要为每行设置一个计数器，LRU算法是把命中行的计数器清零，其他各行计数器加1。当需要替换时淘汰行计数器计数值最大的数据行出局。这是一种高效、科学的算法，其计数器清零过程可以把一些频繁调用后再不需要的数据（对应计数值最大的数据）淘汰出Cache，提高Cache的利用率。

Cache相对于主存来说容量是极其有限的，因此无论如何实现Cache的存储机制（后文缓存关联系将会详细说明），如果不采取合适的替换算法，那么随着Cache的使用不可避免会出现Cache中所有Cache Line都被占用导致需要缓存新的内存块时无法分配Cache Line的情况；或者是根据Cache的存储机制，为该内存块分配的Cache Line正在使用中。以上两点均会导致新的内存块无Cache Line存放，这叫做Cache Line Conflict。

## CPU缓存架构

至此，我们大致能够得到一个CPU缓存架构了：

![k8Wd6P.png](https://s2.ax1x.com/2019/02/02/k8Wd6P.png)

如图当CPU试图通过某一存储单元地址访问数据时，它会自上而下依次从L1、L2、L3、主存中查找，若找到则直接返回对应Cache中的数据而不再向下查找，如果L1、L2、L3都cache miss了，那么CPU将不得不通过总线访问主存或者硬盘上的数据。且通过下图所示的各硬件存取操作所需的时钟周期（cycle，CPU主频的倒数就是一个时钟周期）可以知道，自上而下，存取开销越来越大，因此Cache的设计需尽可能地提高缓存命中率，否则如果到最后还是要到内存中存取将得不偿失。

![k8hcMq.png](https://s2.ax1x.com/2019/02/02/k8hcMq.png)

为了方便大家理解，笔者摘取了*酷壳*中的一篇段子：

> 我们知道计算机的计算数据需要从磁盘调度到内存，然后再调度到L2 Cache，再到L1 Cache，最后进CPU寄存器进行计算。
>
> 给老婆在电脑城买本本的时候向电脑推销人员问到这些参数，老婆听不懂，让我给她解释，解释完后，老婆说，“原来电脑内部这么麻烦，怪不得电脑总是那么慢，直接操作内存不就快啦”。我是那个汗啊。
>
> 我只得向她解释，这样做是为了更快速的处理，她不解，于是我打了下面这个比喻——这就像我们喂宝宝吃奶一样：
>
> - CPU就像是已经在宝宝嘴里的奶一样，直接可以咽下去了。需要1秒钟
>
> - L1缓存就像是已冲好的放在奶瓶里的奶一样，只要把孩子抱起来才能喂到嘴里。需要5秒钟。
>
> - L2缓存就像是家里的奶粉一样，还需要先热水冲奶，然后把孩子抱起来喂进去。需要2分钟。
>
> - 内存RAM就像是各个超市里的奶粉一样，这些超市在城市的各个角落，有的远，有的近，你先要寻址，然后还要去商店上门才能得到。需要1-2小时。
> - 硬盘DISK就像是仓库，可能在很远的郊区甚至工厂仓库。需要大卡车走高速公路才能运到城市里。需要2-10天。
>
> 所以，在这样的情况下——
>
> - 我们不可能在家里不存放奶粉。试想如果得到孩子饿了，再去超市买，这不更慢吗？
>
> - 我们不可以把所有的奶粉都冲好放在奶瓶里，因为奶瓶不够。也不可能把超市里的奶粉都放到家里，因为房价太贵，这么大的房子不可能买得起。
>
> - 我们不可能把所有的仓库里的东西都放在超市里，因为这样干成本太大。而如果超市的货架上正好卖完了，就需要从库房甚至厂商工厂里调，这在计算里叫换页，相当的慢。

## Cache结构和缓存关联性

### 如果让你来设计这样一个Cache，你会如何设计？

如果你跟笔者一样非科班出身，也许会觉得使用哈希表是一个不错的选择，一个内存块对应一条记录，使用内存块的地址的哈希值作为键，使用内存块存储的数据作为值，时间复杂度`O(1)`内完成查找，简单又高效。

但是如果你每一次缓存内存块前都对地址做哈希运算，那么所需时间可能会远远大于Cache存取所需的几十个时钟周期时间，并且这可不是我们应用程序常用的memcache，这里的Cache是实实在在的硬件，在硬件层面上去实现一个对内存地址哈希的逻辑未免有些赶鸭子上架的味道。

以我们常见的X86芯片为例，Cache的结构下图所示：整个Cache被分为S个组，每个组又有E行个最小的存储单元——Cache Line所组成，而一个Cache Line中有B（B=64）个字节用来存储数据，即每个Cache Line能存储64个字节的数据，每个Cache Line又额外包含1个有效位（`valid bit`）、**t**个标记位（`tag bit`），其中`valid bit`用来表示该*缓存行是否有效*；`tag bit`用来*协助寻址*，*唯一标识存储在Cache Line中的块*；而**Cache Line里的64个字节其实是对应内存地址中的数据拷贝**。根据Cache的结构，我们可以推算出每一级Cache的大小为B×E×S。

![](https://ws3.sinaimg.cn/large/006zweohgy1fzsekg99n8j30gc0c90u0.jpg)



缓存设计的一个关键决定是确保每个主存块(block)能够存储在任何一个缓存槽里，或者只是其中一些（此处一个槽位就是一个缓存行）。

有三种方式将缓存槽映射到主存块中：

1. **直接映射(Direct mapped cache)**
   每个内存块只能映射到一个特定的缓存槽。一个简单的方案是通过块索引block_index映射到对应的槽位(block_index % cache_slots)。被映射到同一内存槽上的两个内存块是不能同时换入缓存的。（注：block_index可以通过物理地址/缓存行字节计算得到）
2. **N路组关联(N-way set associative cache)**
   每个内存块能够被映射到N路特定缓存槽中的任意一路。比如一个16路缓存，每个内存块能够被映射到16路不同的缓存槽。一般地，具有一定相同低bit位地址的内存块将共享16路缓存槽。（译者注：相同低位地址表明相距一定单元大小的连续内存）
3. **完全关联(Fully associative cache)**
   每个内存块能够被映射到任意一个缓存槽。操作效果上相当于一个散列表。

其中N路组关联是根据另外两种方式改进而来，是现在的主流实现方案。下面将对这三种方式举例说明。

### Fully associative cache

Fully associative，顾名思义全关联。就是说对于要缓存的一个内存块，可以被缓存在Cache的任意一个Slot（即缓存行）中。以32位操作系统（意味着到内存寻址时是通过32位地址）为例，比如有一个`0101...10 000000 - 0101...10 111111`（为了节省版面省略了高26位中的部分bit位，这个区间代表高26位相同但低6位不同的64个地址，即64字节的内存块）内存块需要缓存，那么它将会被随机存放到一个可用的Slot中，并将高26位作为该Slot的`tag bit`（前文说到每行除了存储内存块的64字节Cache Line，还额外有1个bit标识该行是否有效和t个bit作为该行的唯一ID，本例中t就是26）。这样当内存需要存取这个地址范围内的数据地址时，首先会去Cache中找是否缓存了高26位（`tag bit`）为`0101...10`的Slot，如果找到了再根据数据地址的低6位定位到Cache Line的某个存储单元上，这个低6位称为字节偏移（word offset）

可能你会觉得这不就是散列表吗？的确，它在决定将内存块放入哪个可用的Slot时是随机的，但是它并没有将数据地址做哈希运算并以哈希值作为`tag bit`，因此和哈希表还是有本质的区别的。

此种方式没有得到广泛应用的原因是，内存块会被放入哪个Slot是未知的，因此CPU在根据数据地址查找Slot时需要将数据地址的高位（本例中是高26位）和Cache中的所有Slot的`tag bit`做线性查找，以我的L1 128KB为例，有128 * 1024 / 64 = 2048个Slot，虽然可以在硬件层面做并行处理，但是效率并不可观。

### Direct Mapped Cache

这种方式就是首先将主存中的内存块和Cache中的Slot分别编码得到`block_index`和`slot_index`，然后将`block_index`对`slot_index`取模从而决定某内存块应该放入哪个Slot中，如下图所示：

![image](https://ws4.sinaimg.cn/large/006zweohgy1fzt18xkdxpj30pp0cg76z.jpg)

下面将以我的L1 Cache 128KB，内存4GB为例进行分析：

4GB内存的寻址范围是`000...000`（32个0）到`111...111`（32个1），给定一个32位的数据地址，如何判断L1 Cache中是否缓存了该数据地址的数据？

首先将32位地址分成如下三个部分：

![image](https://wx1.sinaimg.cn/large/006zweohgy1fzt2mu1266j30m007mt9k.jpg)

如此的话对于给定的32位数据地址，首先不管低6位，取出中间的`slot offset`个bit位，定位出是哪一个Slot，然后比较该Slot的`tag bit`是否和数据地址的剩余高位匹配，如果匹配那么表示Cache Hit，最后在根据低6位从该Slot的Cache Line中找到具体的存储单元进行存取数据。

Direct Mapped Cache的缺陷是，低位相同但高位不同的内存块会被映射到同一个Slot上（因为对SlotCount取模之后结果相同），如果碰巧CPU请求存取这些内存块，那么将只有一个内存块能够被缓存到Cache中对应的Slot上，也就是说容易发生Cache Line Conflict。

### N-Way Set Associative Cache

N路组关联，是对Direct Mapped Cache和Full Associative Cache的一个结合，思路是不要对于给定的数据地址就定死了放在哪个Slot上。

如同上文给出的x86的Cache结构图那样，先将Cache均分成S个组，每个组都有E个Slot。假设将我的L1 Cache 128KB按16个Slot划分为一个组，那么组数为：`128 * 1024 / 64`（Slot数）/ 16 = 128 个组（我们将每个组称为一个Set，表示一组Slot的集合）。如此的话，对于给定的一个数据地址，仍将其分为以下三部分：

![image](https://wx1.sinaimg.cn/large/006zweohgy1fzt2wb3vfnj30m007rt9k.jpg)

与Direct Mapped Cache不同的地方就是将原本表示映射到哪个Slot的11个中间bit位改成了用7个bit位表示映射到哪个Set上，在确定Set之后，内存块将被放入该Set的哪个Slot是随机的（可能当时哪个可以用就放到哪个了），然后以剩余的高位19个bit位作为最终存放该内存块的`tag bit`。

这样做的好处就是，对于一个给定的数据地址只会将其映射到特定的Set上，这样就大大减小了Cache Line Conflict的几率，并且CPU在查找Slot时只需在具体的某个Set中线性查找，而Set中的Slot个数较少（分组分得越多，每个组的Slot就越少），这样线性查找的时间复杂度也近似O(1)了。

# 如何编写对Cache Hit友好的程序

通过前面对CPU读写模型的理解，我们知道一旦CPU要从内存中访问数据就会产生一个较大的时延，程序性能显著降低，所谓远水救不了近火。为此我们不得不提高Cache命中率，也就是充分发挥**局部性原理**。

局部性包括时间局部性、空间局部性。

- **时间局部性**：对于**同一数据可能被多次使用**，自第一次加载到Cache Line后，后面的访问就可以多次从Cache Line中命中，从而提高读取速度（而不是从下层缓存读取）。
- **空间局部性**：一个Cache Line有64字节块，我们可以**充分利用一次加载64字节的空间，把程序后续会访问的数据，一次性全部加载进来**，从而提高Cache Line命中率（而不是重新去**寻址**读取）。

## 读取时尽量读取相邻的数据地址

首先来看一下遍历二维数组的两种方式所带来的不同开销：

```java
static int[][] arr = new int[10000][10000];
public static void main(String[] args) {
    m1();		//输出 16
    m2();		//输出 1202	每次测试的结果略有出入
}
public static void m1() {
    long begin = System.currentTimeMillis();
    int a;
    for (int i = 0; i < arr.length; i++) {
        for (int j = 0; j < arr[i].length; j++) {
            a = arr[i][j];
        }
    }
    long end = System.currentTimeMillis();
    System.out.println(end - begin + "================");
}
public static void m2() {
    long begin = System.currentTimeMillis();
    int a;
    for (int j = 0; j < arr[0].length; j++) {
        for (int i = 0; i < arr.length; i++) {
            a = arr[i][j];
        }
    }
    long end = System.currentTimeMillis();
    System.out.println(end - begin + "================");
}
```

经过多次测试发现逐列遍历的效率明显低于逐行遍历，这是因为按行遍历时数据地址是相邻的，因此可能会对连续16个`int`变量（16x4=64字节）的访问都是访问同一个Cache Line中的内容，在访问第一个`int`变量并将包括其在内连续64字节加入到Cache Line之后，对后续`int`变量的访问直接从该Cache Line中取就行了，不需要其他多余的操作。而逐列遍历时，如果列数超多16，意味着一行有超过16个`int`变量，每行的起始地址之间的间隔超过64字节，那么每行的`int`变量都不会在同一个Cache Line中，这会导致Cache Miss重新到内存中加载内存块，并且每次跨缓存行读取，都会比逐行读取多一个Hit Latency的开销。

上例中的`i`、`j`体现了时间局部性，`i`、`j`作为循环计数器被频繁操作，将被存放在寄存器中，CPU每次都能以最快的方式访问到他们，而不会从Cache、主存等其他地方访问。

而优先遍历一行中相邻的元素则利用了空间局部性，一次性加载地址连续的64个字节到Cache Line中有利于后续相邻地址元素的快速访问。

## Cache Consistency & Cache Lock & False Sharing

那么是不是任何时候，操作同一缓存行比跨缓存行操作的性能都要好呢？没有万能的机制，只有针对某一场景最合适的机制，连续紧凑的内存分配（Cache的最小存储单位是Cache Line）也有它的弊端。

这个弊端就是缓存一致性引起的，由于每个CPU核心都有自己的Cache（通常是L1和L2），并且大多数情况下都是各自访问各自的Cache，这很有可能导致各Cache中的数据副本以及主存中的共享数据之间各不相同，有时我们需要调用各CPU相互协作，这时就不得不以主存中的共享数据为准并让各Cache保持与主存的同步，这时该怎么办呢？

这个时候缓存一致性协议就粉墨登场了：如果（各CPU）你们想让缓存行和主存保持同步，你们都要按我的规则来修改共享变量

> 这是一个跟踪每个缓存行的状态的缓存子系统。该系统使用一个称为 *“总线动态监视”* 或者称为*“总线嗅探”* 的技术来监视在系统总线上发生的所有事务，以检测缓存中的某个地址上何时发生了读取或写入操作。
>
> 当这个缓存子系统在系统总线上检测到对缓存中加载的内存区域进行的读取操作时，它会将该缓存行的状态更改为 **“shared”**。如果它检测到对该地址的写入操作时，会将缓存行的状态更改为 **“invalid”**。
>
> 该缓存子系统想知道，当该系统在监视系统总线时，系统是否在其缓存中包含数据的惟一副本。如果数据由它自己的 CPU 进行了更新，那么这个缓存子系统会将缓存行的状态从 **“exclusive”** 更改为 **“modified”**。如果该缓存子系统检测到另一个处理器对该地址的读取，它会阻止访问，更新系统内存中的数据，然后允许该处理的访问继续进行。它还允许将该缓存行的状态标记为 **shared**。

简而言之就是各CPU都会通过总线嗅探来监视其他CPU，一旦某个CPU对自己Cache中缓存的共享变量做了修改（能做修改的前提是共享变量所在的缓存行的状态不是无效的），那么就会导致其他缓存了该共享变量的CPU将该变量所在的Cache Line置为无效状态，在下次CPU访问无效状态的缓存行时会首先要求对共享变量做了修改的CPU将修改从Cache写回主存，然后自己再从主存中将最新的共享变量读到自己的缓存行中。

并且，缓存一致性协议通过**缓存锁定**来保证CPU修改缓存行中的共享变量并通知其他CPU将对应缓存行置为无效这一操作的原子性，即当某个CPU修改位于自己缓存中的共享变量时会禁止其他也缓存了该共享变量的CPU访问自己缓存中的对应缓存行，并在缓存锁定结束前通知这些CPU将对应缓存行置为无效状态。

> 在缓存锁定出现之前，是通过总线锁定来实现CPU之间的同步的，即CPU在回写主存时会锁定总线不让其他CPU访问主存，但是这种机制开销较大，一个CPU对共享变量的操作会导致其他CPU对其他共享变量的访问。

缓存一致性协议虽然保证了Cache和主存的同步，但是又引入了一个新的的问题：伪共享（False Sharing）。

如下图所示，数据X、Y、Z被加载到同一Cache Line中，线程A在Core1修改X，线程B在Core2上修改Y。根据MESI（可见文尾百科链接）大法，假设是Core1是第一个发起操作的CPU核，Core1上的L1 Cache Line由S（共享）状态变成M（修改，脏数据）状态，然后告知其他的CPU核，图例则是Core2，引用同一地址的Cache Line已经无效了；当Core2发起写操作时，首先导致Core1将X写回主存，Cache Line状态由M变为I（无效），而后才是Core2从主存重新读取该地址内容，Cache Line状态由I变成E（独占），最后进行修改Y操作， Cache Line从E变成M。可见多个线程操作在同一Cache Line上的不同数据，相互竞争同一Cache Line，导致线程彼此牵制影响（这一行为称为乒乓效应），变成了串行程序，降低了并发性。此时我们则需要将共享在多线程间的数据进行隔离，使他们不在同一个Cache Line上，从而提升多线程的性能。

![](https://ws1.sinaimg.cn/large/006zweohgy1fzt4s68faxj30e30c40ta.jpg)

Cache Line伪共享的两种解决方案：

- 缓存行填充（Cache Line Padding），通过增加两个变量的地址距离使之位于两个不同的缓存行上，如此对共享变量X和Y的操作不会相互影响。
- 线程不直接操作全局共享变量，而是将全局共享变量读取一份副本到自己的局部变量，局部变量在线程之间是不可见的因此随你线程怎么玩，最后线程再将玩出来的结果写回全局变量。

## Cache Line Padding

著名的并发大师Doug Lea就曾在JDK7的`LinkedTransferQueue`中通过追加字节的方式提高队列的操作效率：

```java
public class LinkedTransferQueue<E>{
    private PaddedAtomicReference<QNode> head;
    private PaddedAtomicReference<QNode> tail;
    static final class PaddedAtomicReference<E> extends AtomicReference<T{
        //给对象追加了 15 * 4 = 60 个字节
        Object p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, pa, pb, pc, pd, pe;
        PaddedAtomicReference(T r){
            super(r);
        }
    }
}
public class AtomicReference<V> implements Serializable{
    private volatile V value;
}
```

你能否看懂第6行的用意？这还要从对象的内存布局说起，读过《深入理解Java虚拟机（第二版）》的人应该知道非数组对象的内存布局是这样的

- 对象头

  对象头又分为一下三个部分：

  - Mark Word，根据JVM的位数不同表现为32位或64位，存放对象的hashcode、分代年龄、锁标志位等。该部分的数据可被复用，指向偏向线程的ID或指向栈中的Displaced Mark Word又或者指向重量级锁。
  - Class Mete Data，类型指针（也是32位或64位），指向该对象所属的类字节码在被加载到JVM之后存放在方法区中的类型信息。
  - Array Length，如果是数组对象会有这部分数据。

- 实例数据

  运行时对象所包含的数据，是可以动态变化的，而且也是为各线程所共享的，这部分的数据又由以下类型的数据组成：

  - byte, char, short, int, float，占四个字节（注意这是JVM中的数据类型，而不是Java语言层面的数据类型，两者还是有本质上的不同的，由于JVM指令有限，因此不足4个自己的数据都会使用int系列的指令操作）。
  - long,double，占8个字节。
  - reference，根据虚拟机的实现不同占4个或8个字节，但32位JVM中引用类型变量占4个字节。

- 对齐填充

  这部分数据没有实质性的作用，仅做占位目的。对于Hotspot JVM来说，它的内存管理是以8个字节为单位的，而非数组对象的对象头刚好是8个字节（32位JVM）或16个字节（64位JVM），因此当实例数据不是8个字节的倍数时用来做对齐填充。

搞清楚对象内存布局之后我们再来看一下上述中的代码，在性能较高的32位JVM中，引用变量占4个字节，如此的话`PaddedAtomicReference`类型的对象光实例数据部分就包含了`p0-pe`15个引用变量，再加上从父类`AtomicReference`中继承的一个引用变量一共是16个，也就是说光实例数据部分就占了64个字节，因此对象`head`和`tail`一定不会被加载到同一个缓存行，这样的话对队列头结点和为尾结点的操作不会因为缓存锁定而串行化，也不会发生互相牵制的乒乓效应，提高了队列的并发性能。

# 并发编程三要素

经过上述CPU Cache的洗礼，我们总算能够进入Java并发编程了，如果你真正理解了Cache，那么理解Java并发模型就很容易了。

并发编程的三要素是：原子性、可见性、有序性。

## 可见性

不可见问题是CPU Cache机制引起的，CPU不会直接访问主存而时大多数时候都在操作Cache，由于每个线程可能会在不同CPU核心上进行上下文切换，因此可以理解为每个线程都有自己的一份“本地内存”，当然这个本地内存不是真实存在的，它是对CPU Cache的一个抽象：

![image](https://ws2.sinaimg.cn/large/006zweohgy1fzt8l24j96j30ad0a4748.jpg)

如果线程`Thread-1`在自己的本地内存中修改共享变量的副本时如果不及时刷新到主存并通知`Thread-2`从主存中重新读取的话，那么`Thread-2`将看不到`Thread-1`所做的改变并仍然我行我素的操作自己内存中的共享变量副本。这也就是我们常说的Java内存模型（JMM）。

那么线程该如何和主存交互呢？JMM定义了以下8种操作以满足线程和主存之间的交互，JVM实现必须满足对所有变量进行下列操作时都是原子的、不可再分的（对于double和long类型的变量来说，load、store、read、write操作在某些平台上允许例外）

- lock，作用于主内存的变量，将一个对象标识为一条线程独占的状态
- unlock，作用于主内存的变量，将一个对象从被锁定的状态中释放出来
- read，从主存中读取变量
- load，将read读取到的变量加载本地内存中
- use，将本地内存中的变量传送给执行引擎，每当JVM执行到一个需要读取变量的值的字节码指令时会执行此操作
- assign，把从执行引擎接收到的值赋给本地内存中的变量，每当JVM执行到一个需要为变量赋值的字节码指令时会执行此操作。
- store，线程将本地内存中的变量写回主存
- write，主存接受线程的写回请求更新主存中的变量

如果需要和主存进行交互，那么就要顺序执行`read`、`load`指令，或者`store`、`write`指令，注意，这里的顺序并不意味着连续，也就是说对于共享变量`a`、`b`可能会发生如下操作`read a -> read b -> load b -> load`。

如此也就能理解本文开头的第一个示例代码的运行结果了，因为`t2`线程的执行`sharedVariable = oldValue`需要分三步操作：`assign -> store -> write`，也就是说`t2`线程在自己的本地内存对共享变量副本做修改之后（`assign`）、执行`store`、`write`将修改写回主存之前，`t2`可以插进来读取共享变量。而且就算`t2`将修改写回到主存了，如果不通过某种机制通知`t1`重新从主存中读，`t1`还是会守着自己本地内存中的变量发呆。

为什么`volatile`能够保证变量在线程中的可见性？因为JVM就是通过`volatile`调动了缓存一致性机制，如果对使用了`volatile`的程序，查看JVM解释执行或者JIT编译后生成的汇编代码，你会发现对`volatile`域（被`volatile`修饰的共享变量）的写操作生成的汇编指令会有一个`lock`前缀，该`lock`前缀表示JVM会向CPU发送一个信号，这个信号有两个作用：

- 对该变量的改写立即刷新到主存（也就是说对`volatile`域的写会导致`assgin -> store -> write`的原子性执行）
- 通过总线通知其他CPU该共享变量已被更新，对于也缓存了该共享变量的CPU，如果接收到该通知，那么会在自己的Cache中将共享变量所在的缓存行置为无效状态。CPU在下次读取读取该共享变量时发现缓存行已被置为无效状态，他将重新到主存中读取。

你会发现这就是在底层启用了缓存一致性协议。也就是说对共享变量加上了`volatile`之后，每次对`volatile`域的写将会导致此次改写被立即刷新到主存并且后续任何对该`volatile`域的读操作都将重新从主存中读。

## 原子性

原子性是指一个或多个操作必须连续执行不可分解。上述已经提到，JMM提供了8个原子性操作，下面通过几个简单的示例来看一下在代码层面，哪些操作是原子的。

对于`int`类型的变量`a`和`b`：

1. `a = 1`

   这个操作是原子的，字节码指令为`putField`，属于`assign`操作

2. `a = b`

   这个操作不是原子的，需要先执行`getField`读变量`b`，再执行`putField`对变量`a`进行赋值

3. `a++`

   实质上是`a = a + 1`，首先`getField`读取变量`a`，然后执行`add`计算`a + 1`的值，最后通过`putField`将计算后的值赋值给`a`

4. `Object obj = new Object()`

   首先会执行`allocMemory`为对象分配内存，然后调用`<init>`初始化对象，最后返回对象内存地址，更加复杂，自然也不是原子性的。

## 有序性

由于CPU具有多个不同类型的指令执行单元，因此一个时钟周期可以执行多条指令，为了尽可能地提高程序的并行度，CPU会将不同类型的指令分发到各个执行单元同时执行，编译器在编译过程中也可能会对指令进行重排序。

比如：

```java
a = 1;
b = a;
flag = true;
```

`flag = true`可以重排序到`b = a`甚至`a = 1`前面，但是编译器不会对存在依赖关系的指令进行重排序，比如不会将`b = a`重排序到`a = 1`的前面，并且编译器将通过插入指令屏障的方式也禁止CPU对其重排序。

对于存在依赖关系两条指令，编译器能够确保他们执行的先后顺序。但是对于不存在依赖关系的指令，编译器只能确保书写在前面的先行发生于书写在后面的，比如`a = 1`先行发生于`flag = true`，但是`a = 1`在`flag = true`之前执行，先行发生仅表示`a = 1`这一行为对`flag = true`可见。

### happens-before

在Java中，有一些天生的先行发生原则供我们参考，通过这些规则我们能够判断两条程序的有序性（即是否存在一个先行发生于另一个的关系），从而决定是否有必要对其采取同步。

- 程序顺序规则：在单线程环境下，按照程序书写顺序，书写在前面的程序 happens-before 书写在后面的。
- `volatile`变量规则：对一个`volatile`域的写 happens-before 随后对同一个`volatile`域的读。
- 监视器规则：一个线程释放其持有的锁对象 happens-before 随后其他线程（包括这个刚释放锁的线程）对该对象的加锁。
- 线程启动规则：对一个线程调用`start`方法 happens-before 执行这个线程的`run`方法
- 线程终止规则：`t1`线程调用`t2.join`，检测到`t2`线程的执行终止 happens-before `t1`线程从`join`方法返回
- 线程中断规则：对一个线程调用`interrupt`方法 happens-before 这个线程响应中断
- 对象终结规则：对一个对象的创建`new` happens-before 这个对象的`finalize`方法被调用
- 传递性：如果A happens-before B且B happens-before C，则有A happens-before C

通过以上规则我们解决本文开头提出的疑惑，为何`synchronized`锁释放、CAS更新和`volatile`写有着相同的语义（即都能够让对共享变量的改写立即对所有线程可见）。

### 锁释放有着volatile域写语义

```java
new Thread(() -> {
    synchronized (lock) {
        int oldValue = sharedVariable;
        while (sharedVariable < MAX) {
            while (!changed) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println(Thread.currentThread().getName() +
                               " watched the change : " + oldValue + "->" + sharedVariable);
            oldValue = sharedVariable;
            changed = false;
            lock.notifyAll();
        }
    }
}， "t1").start();

new Thread(() -> {
    synchronized (lock) {
        int oldValue = sharedVariable;
        while (sharedVariable < MAX) {
            while (changed) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            System.out.println(Thread.currentThread().getName() +
                               " do the change : " + sharedVariable + "->" + (++oldValue));
            sharedVariable = oldValue;
            changed = true;
            lock.notifyAll();
        }
    }
}， "t2").start();
```

1. 对于`t2`单个线程使用程序顺序规则，第`34`行对共享变量`sharedVariable`的写 happens-before 第 `38`行退出临界区释放锁。
2. 对于`t1`、`t2`的并发运行，第`38`行`t2`对锁的释放 happens-before 第`2`行`t1`对锁的获取。
3. 同样根据程序顺序规则，第`2`行锁获取 happens-before 第 `13`行对共享变量`sharedVariable`的读。
4. 依据上述的1、2、3和传递性，可得第`34`行对共享变量`sharedVariable`的写 happens-before 第`13`行对共享变量`sharedVariable`的读。

> 总结：通过对共享变量写-读的前后加锁，是的普通域的写-读有了和volatile域写-读相同的语义。

### 原子类CAS更新有着volatile域写语义

前文已说过，对于基本类型或引用类型的读取（`use`）和赋值（`assign`），JMM要求JVM实现来确保原子性。因此这类操作的原子性不用我们担心，但是复杂操作的原子性该怎么保证呢？

一个很典型的例子，我们启动十个线程对共享变量`i`执行10000次`i++`操作，结果能达到我们预期的100000吗？

```java
private static volatile int i = 0;

public static void main(String[] args) throws InterruptedException {
    ArrayList<Thread> threads = new ArrayList<>();
    Stream.of("t0","t2","t3","t4","t5","t6","t7","t8","t9" ).forEach(
        threadName -> {
            Thread t = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    i++;
                }
            }, threadName);
            threads.add(t);
            t.start();
        }
    );
    for (Thread thread : threads) {
        thread.join();
    }
    System.out.println(i);	
}
```

笔者测试了几次都没有达到预期。

也许你会说给`i`加上`volatile`就行了，真的吗？你不妨试一下。

如果你理性的分析一下即使是加上`volatile`也不行。因为`volatile`只能确保变量`i`的可见性，而不能保证对其复杂操作的原子性。`i++`就是一个复杂操作，它可被分解为三步：读取i、计算i+1、将计算结果赋值给i。

要想达到预期，必须使这一次的`i++` happens-before 下一次的`i++`，既然这个程序无法满足这一条件，那么我们可以手动添加一些让程序满足这个条件的代码。比如将`i++`放入临界区，这是利用了监视器规则，我们不妨验证一下：

```java
private static int i = 0;
private static Object lock = new Object();
public static void main(String[] args) throws InterruptedException {
    ArrayList<Thread> threads = new ArrayList<>();
    Stream.of("t0","t1","t2","t3","t4","t5","t6","t7","t8","t9" ).forEach(
        threadName -> {
            Thread t = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    synchronized (lock) {
                        i++;
                    }
                }
            }, threadName);
            threads.add(t);
            t.start();
        }
    );
    for (Thread thread : threads) {
        thread.join();
    }
    System.out.println(i);	//10000
}
```

运行结果证明我们的逻辑没错，这就是有理论支撑的好处，让我们有方法可寻！并发不是玄学，只要我们有足够的理论支撑，也能轻易地写出高并准确的代码。正确性是并发的第一要素！在实现这一点的情况下，我们再谈并发效率。

于是我们重审下这段代码的并发效率有没有可以提升的地方？由于`synchronized`会导致同一时刻十个线程只有1个线程能获取到锁，其余九个都将被阻塞，而线程阻塞-被唤醒会导致用户态到内核态的转换（可参考笔者的 Java[线程是如何实现的](http://www.zhenganwen.top/posts/5c6e8cdf/)一文），开销较大，而这仅仅是为了执行以下`i++`？这会导致CPU资源的浪费，吞吐量整体下降。

为了解决这一问题，CAS诞生了。

CAS（Compare And Set）就是一种原子性的复杂操作，它有三个参数：数据地址、更新值、预期值。当需要更新某个共享变量时，CAS将先比较数据地址中的数据是否是预期的旧值，如果是就更新它，否则更新失败不会影响数据地址处的数据。

CAS自旋（循环CAS操作直至更新成功才退出循环）也被称为乐观锁，它总认为并发程度没有那么高，因此即使我这次没有更新成功多试几次也就成功了，这个多试几次的开销并没有线程阻塞的开销大，因此在实际并发程度并不高时比synchronized的性能高许多。但是如果并发程度真的很高，那么多个线程长时间的CAS自旋带来的CPU开销也不容乐观。由于80%的情况下并发都程度都较小，因此常用CAS替代synchronized以获取性能上的提升。

如下是`Unsafe`类中的CAS自旋：

```java
public final int getAndSetInt(Object var1, long var2, int var4) {
    int var5;
    do {
        var5 = this.getIntVolatile(var1, var2);
    } while(!this.compareAndSwapInt(var1, var2, var5, var4));

    return var5;
}
```

CAS操作在x86上是由cmpxchg（Compare Exchange）实现的（不同指令集有所不同）。而Java中并未公开CAS接口，CAS以``compareAndSetXxx`的形式定义在`Unsafe`类（仅供Java核心类库调用）中。我们可以通过反射调用，但是JDK提供的`AtomicXxx`系列原子操作类已能满足我们的大多数需求。

于是我们来看一下启动十个线程执行1000 000次`i++`在使用CAS和使用`synchronized`两种情况下的性能之差：

CAS大约在200左右：

```java
private static AtomicInteger i = new AtomicInteger(0);
public static void main(String[] args) throws InterruptedException {
    ArrayList<Thread> threads = new ArrayList<>();
    long begin = System.currentTimeMillis();
    Stream.of("t0","t1","t2","t3","t4","t5","t6","t7","t8","t9" ).forEach(
        threadName -> {
            Thread t = new Thread(() -> {
                for (int j = 0; j < 10000; j++) {
                    i.getAndIncrement();
                }
            }, threadName);
            threads.add(t);
            t.start();
        }
    );
    for (Thread thread : threads) {
        thread.join();
    }
    long end = System.currentTimeMillis();
    System.out.println(end - begin);	//70-90之间
}
```

使用`synchronized`大约在480左右：

```java
private static int i = 0;
private static Object lock = new Object();
public static void main(String[] args) throws InterruptedException {
    ArrayList<Thread> threads = new ArrayList<>();
    long begin = System.currentTimeMillis();
    Stream.of("t0","t1","t2","t3","t4","t5","t6","t7","t8","t9" ).forEach(
        threadName -> {
            Thread t = new Thread(() -> {
                for (int j = 0; j < 1000000; j++) {
                    synchronized (lock) {
                        i++;
                    }
                }
            }, threadName);
            threads.add(t);
            t.start();
        }
    );
    for (Thread thread : threads) {
        thread.join();
    }
    long end = System.currentTimeMillis();
    System.out.println(end - begin);
}
```

但是我们的疑问还没解开，为什么原子类的CAS更新具有`volatile`写的语义？单单CAS只能确保`use -> assgin`是原子的啊。

看一下原子类的源码就知道了，以`AtomicInteger`，其他的都类同：

```java
public class AtomicInteger extends Number implements java.io.Serializable {
    private volatile int value;
    public final int getAndSet(int newValue) {
        return unsafe.getAndSetInt(this, valueOffset, newValue);
    }
}
```

你会发现原子类封装了一个`volatile`域，豁然开朗吧。CAS更新的`volatile`域，我们知道`volatile`域的更新将会导致两件事发生：

- 将改写立即刷新到主存
- 通知其他CPU将缓存行置为无效

# volatile禁止重排序

volatile的另一个语义就是禁止指令重排序，即`volatile`产生的汇编指令`lock`具有个指令屏障使得该屏障之前的指令不能重排序到屏障之后。这个作用使用单例模式的并发优化案例来说再好不过了。

## 懒加载模式

利用类加载过程的初始化（当类被主动引用时应当立即对其初始化）阶段会执行类构造器`<clinit>`按照显式声明为静态变量初始化的特点。（类的主动引用、被动引用、类构造器、类加载过程详见《深入理解Java虚拟机（第二版）》）

```java
public class SingletonObject1 {

    private static final SingletonObject1 instance = new SingletonObject1();

    public static SingletonObject1 getInstance() {
        return instance;
    }

    private SingletonObject1() {

    }
}
```

> 什么是对类的主动引用：
>
> - `new`、`getStatic`、`putStatic`、`invokeStatic`四个字节码指令涉及到的类，对应语言层面就是创建该类实例、读取该类静态字段、修改该类静态字段、调用该类的静态方法
> - 通过`java.lang.reflect`包的方法对该类进行反射调用时
> - 当初始化一个类时，如果他的父类没被初始化，那么先初始化其父类
> - 当JVM启动时，首先会初始化main函数所在的类
>
> 什么是对类的被动引用：
>
> - 通过子类访问父类静态变量，子类不会被立即初始化
> - 通过数组定义引用的类不会被立即初始化
> - 访问某个类的常量，该类不会被立即初始化（因为经过编译阶段的常量传播优化，该常量已被复制一份到当前类的常量池中了）

## 饿汉模式1

需要的时候才去创建实例（这样就能避免暂时不用的大内存对象被提前加载）：

```java
public class SingletonObject2 {

    private static SingletonObject2 instance = null;

    public static SingletonObject2 getInstance() {
        if (SingletonObject2.instance == null) {
            SingletonObject2.instance = new SingletonObject2();
        }
        return SingletonObject2.instance;
    }

    private SingletonObject2() {

    }
}
```

## 饿汉模式2

上例中的饿汉模式在单线程下是没问题的，但是一旦并发调用`getInstance`，可能会出现`t1`线程刚执行完第`6`行还没来得及创建对象，`t2`线程就执行到第`6`行的判断了，这会导致多个线程来到第`7`行并执行，导致`SingletonObject2`被实例化多次，于是我们将第`6-7`行通过`synchronized`串行化：

```java
public class SingletonObject3 {
    private static SingletonObject3 instance = null;

    public static SingletonObject3 getInstance() {
        synchronized (SingletonObject3.class) {
            if (SingletonObject3.instance == null) {
                SingletonObject3.instance = new SingletonObject3();
            }
        }
        return SingletonObject3.instance;
    }

    private SingletonObject3() {

    }

}
```

## DoubleCheckedLocking

我们已经知道`synchronized`是重量级锁，如果单例被实例化后，每次获取实例还需要获取锁，长期以往，开销不菲，因此我们在获取实例时加上一个判断，如果单例已被实例化则跳过获取锁的操作（仅在初始化单例时才可能发生冲突）：

```java
public class SingletonObject4 {

    private static SingletonObject4 instance = null;

    public static SingletonObject4 getInstance() {
        if (SingletonObject4.instance == null) {
            synchronized (SingletonObject4.class){
                if (SingletonObject4.instance == null) {
                    SingletonObject4.instance = new SingletonObject4();
                }
            }
        }
        return SingletonObject4.instance;
    }

    private SingletonObject4() {
        
    }
}
```

## DCL2

这样真的就OK了吗，确实同一时刻只有一个线程能够进入到第9行创建对象，但是你别忘了`new Object()`是可以被分解的！其对应的伪指令如下：

```java
allocMemory 	//为对象分配内存
<init>		    //执行对象构造器
return reference //返回对象在堆中的地址
```

而且上述三步是没有依赖关系的，这意味着他们可能被重排序成下面的样子：

```java
allocMemory 	//为对象分配内存
return reference //返回对象在堆中的地址
<init>		    //执行对象构造器
```

这时可能会导致`t1`线程执行到第`2`行时，`t1`线程判断`instance`引用地址不为`null`于是去使用这个`instance`，而这时对象还没构造完！！这意味着如果对象可能包含的引用变量为`null`而没被正确初始化，如果`t1`线程刚好访问了该变量那么将抛出空指针异常

于是我们利用`volatile`禁止`<init>`重排序到为`instance`赋值之后：

```java
public class SingletonObject5 {
    
    private volatile static SingletonObject5 instance = null;

    public static SingletonObject5 getInstance() {
        if (SingletonObject5.instance == null) {
            synchronized (SingletonObject5.class) {
                if (SingletonObject5.instance == null) {
                    SingletonObject5.instance = new SingletonObject5();
                }
            }
        }
        return SingletonObject5.instance;
    }

    private SingletonObject5() {
        
    }
}
```

## InstanceHolder

我们还可以利用类只被初始化一次的特点将单例定义在内部类中，从而写出更加优雅的方式：

```java
public class SingletonObject6 {
    
    private static class InstanceHolder{
        public static SingletonObject6 instance = new SingletonObject6();    
    }

    public static SingletonObject6 getInstance() {
        return InstanceHolder.instance;
    }

    private SingletonObject6() {
        
    }

}
```

## 枚举实例的构造器只会被调用一次

这是由JVM规范要求的，JVM实现必须保证的。

```java
public class SingletonObject7 {
    
    private static enum Singleton{
        INSTANCE;

        SingletonObject7 instance;
        private Singleton() {
            instance = new SingletonObject7();
        }
    }

    public static SingletonObject7 getInstance() {
        return Singleton.INSTANCE.instance;
    }

    private SingletonObject7() {
        
    }

}
```



（全文完）

# 参考链接

- [一篇对伪共享、缓存行填充和CPU缓存讲的很透彻的文章](https://blog.csdn.net/qq_27680317/article/details/78486220)
- [对于CPU Cache应该知道的事儿](http://cenalulu.github.io/linux/all-about-cpu-cache/)
- [7个示例科普CPU Cache](http://coolshell.cn/articles/10249.html) -> [英文原文](http://igoro.com/archive/gallery-of-processor-cache-effects/)
- [伪共享](https://mechanical-sympathy.blogspot.com/2011/07/false-sharing.html)

缓存一致性协议：

- [MSI](https://zh.wikipedia.org/wiki/MSI%E5%8D%8F%E8%AE%AE)
- [MESI](https://zh.wikipedia.org/wiki/MESI%E5%8D%8F%E8%AE%AE)