# RokidSocketDemo

# 目标
一个socket基础框架，支持通过UDP组播方式搜索局域网内设备，之后通过TCP建立一对多的TCP连接。支持字符串和小图片的发送和接收。

# 对外接口
1. 启动socket服务
2. 关闭socket服务
3. 发送消息
  a) 客户端->服务端
  b) 服务端->某个客户端
  c）服务端广播给所有客户端
4. 通过回调方式通知收到的消息

# 使用

```
implementation 'com.rokid:glass.socket:1.0.0'
```


### TODO：
需要加入鉴权机制，只有经过授权的设备才能连接服务端

