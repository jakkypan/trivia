# C++实例化说明

说明以下几个事情：

* 1、点(.)和箭头(->)的区别
* 2、Object和Object *的区别
* 3、实例化new和不new的区别
* 4、实例化是有括号和无括号的区别

测试例子：

```c
class Test
{
public:
    Test(): num(10)
    {
        std::cout << "init test with default num: " << num << std::endl;
    }

    Test(int i): num(i)
    {
        std::cout << "init test with num: " << num << std::endl;
    }

    void printNum()
    {
        std::cout << "num: " <<num << std::endl;
    }

private:
    int num;
};
```

## 问题1和2

```c
Test *c1;
c1->printNum();
Test c2;
c2.printNum();

# =====result=====
num: 1576174549
init test with default num: 10
num: 10
```

c++中当定义类对象是指针对象时候，就需要用到`->`指向类中的成员；当定义一般对象时候时就需要用到`.`指向类中的成员。

这个例子更能说明这个问题：

```c
#include <iostream>
 
using namespace std;
 
class Car
{
public:
	int number;
 
	void Create() 
	{
		cout << "Car created, number is:  " << number << "\n" ;
	}
};
 
int main() {
	Car x;
	// declares x to be a Car object value,
	// initialized using the default constructor
	// this very different with Java syntax Car x = new Car();
	x.number = 123;
	x.Create();
 
	Car *y; // declare y as a pointer which points to a Car object
	y = &x; // assign x's address to the pointer y
	(*y).Create(); // *y is object
	y->Create(); // same as previous line, y points to x object. It stores a reference(memory address) to x object.
 
	y->number = 456; // this is equal to (*y).number = 456;
	y->Create();
}
```

`Object`和`Object *`的区别除了一个产生的是对象，一个是对象指针之外，最大的区别是`Object *`不会触发构造函数的调用。

还有个区别就是堆和栈的区别，可以看下面的[扩展部分]()

## 问题3

```c
Test *c1;  // ok
Test c2;  // ok
Test c3 = new Test;  // compile error,no viable conversion from 'Test *' to 'Test'
Test *c4 = new Test;  // ok
```

就是说，正常new出来的都是对象指针，无法通过new来产生一个对象。



## 问题4

```c
Test *c3 = new Test;
c3->printNum();
Test *c4 = new Test();
c4->printNum();

# =====result=====
init test with default num: 10
num: 10
init test with default num: 10
num: 10
```

不论有没有定义默认构造函数（**如果用户定义的类中没有显式的定义任何构造函数，编译器就会自动为该类型生成默认构造函数，称为合成的构造函数（synthesized default constructor）**），那么class c = nw clases;和class c = new class();一样，都会调用默认构造函数。

但对于内置类型呢？

```c
int *p1 = new int[10];
std::cout << "num1: " <<p1[1] << std::endl;
int *p2 = new int[10]();
std::cout << "num2: " <<p2[1] << std::endl;

# =====result=====
num1: -1073741824
num2: 0
```

对于内置类型：**`int *a = new int`不会将申请到的int空间初始化，而`int *a = new int()`则会将申请到的int空间初始化为0。所以建议对于内置类型使用带括号的new。**

## 扩展1：在堆上创建对象，还是在栈上？

具体参考[C++：在堆上创建对象，还是在栈上？](https://blog.csdn.net/Solo_two/article/details/79780086)

```c
Object obj;
```

这行语句的含义是，使对象obj具有**自动存储（automatic storage）**的性质。所谓“自动存储”，意思是这个对象的存储位置取决于其声明所在的上下文。

* 如果这个语句出现在函数内部，那么它就在栈上创建对象
* 如果这个语句不是在函数内部，而是作为一个类的成员变量，则取决于这个类的对象是如何分配的

```c
class Class
{
    Object obj;
};
 
Class *pClass = new Class;
or
Class *pClass;
```
这个就是在堆上分配的。

```c
class Class
{
    Object obj;
};
 
Class pClass;
```
这个就是在栈上分配的。

并不是说指针指向的对象都是在堆上创建的。下面的代码则使用指针指向一个在栈上创建的对象：

```c
Object obj;
Object *pObj = &obj;
```

## 扩展2：自动存储、静态存储和动态存储

具体参考[C++中管理数据内存的方式](https://www.jianshu.com/p/52015ac04f67)

