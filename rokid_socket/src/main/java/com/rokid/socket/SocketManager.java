package com.rokid.socket;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.IBinder;

import com.rokid.socket.bean.MessageEvent;
import com.rokid.socket.bean.SocketDevice;
import com.rokid.socket.callback.IClientCallback;
import com.rokid.socket.callback.IServiceCallback;
import com.rokid.socket.service.TCPService;
import com.rokid.socket.service.UDPService;
import com.rokid.socket.utils.Logger;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.LinkedHashMap;

/**
 * @author: zhuo_hf@foxmail.com
 * @version: 1.0
 * Create Time: 2019/3/10
 */
public class SocketManager {

    // 保存当前UDP线程是服务端，还是客户端
    public enum SocketMode {
        SERVER, CLIENT
    }

    public enum SocketStatus {
        UNKNOW, CONNECTING, CONNECTED, DISCONNECT
    }

    private SocketMode mMode;

    private Context mContext;

    private WifiManager.MulticastLock mMulticastLock;

    // 单实例
    private static SocketManager mInstance = null;
    // 组播的IP地址
    public final static String UDP_IP = "228.5.6.7";//"239.9.9.1";
    // 组播的Port
    public final static Integer UDP_PORT = 6789; //5761;//17375;
    // tcp的Port
    public final static Integer TCP_PORT = 6761;//17375;

    // 为了避免端口被占用，这里定义一个变量可以循环搜索可以试用的端口
    public Integer portServer = TCP_PORT;

    private TCPService mTCPService;

    private UDPService mUDPService;

    private IClientCallback mClientCallback;
    private IServiceCallback mServiceCallback;


    private SocketManager() {
        /*this.mDevices = new Vector<>();*/
    }

    public static SocketManager getInstance() {
        if (mInstance == null) {
            synchronized (SocketManager.class){
                mInstance = new SocketManager();
            }
        }
        return mInstance;
    }

    /**
     * 启动连接
     * @param context
     * @param mode 设置是手机端，还是设备端
     */
    public void start(Context context, SocketMode mode) {
        Logger.d("启动连接, mode="+mode);
        EventBus.getDefault().register(this);

        this.mContext = context;
        this.mMode = mode;

        // 启动TCP 服务端
        Intent tcpIntent = new Intent(mContext, TCPService.class);
        tcpIntent.putExtra("mode", mMode);
        mContext.bindService(tcpIntent, mTCPServiceConnection, Context.BIND_AUTO_CREATE);

        // 如果是客户端，则立即启动UDP服务器开始监听
        if(mode == SocketMode.CLIENT) {
            Intent udpIntent = new Intent(mContext, UDPService.class);
            udpIntent.putExtra("mode", mMode);
            mContext.bindService(udpIntent, mUDPServiceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    /**
     * 断开连接
     */
    public void stop() {
        Logger.d("关闭连接, mode="+mMode);
        // 解绑TCP服务器
        mContext.unbindService(mTCPServiceConnection);
        mTCPService = null;

        mContext.unbindService(mUDPServiceConnection);
        mUDPService = null;

        if(EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this);
        }
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void handleEvent(MessageEvent messageEvent) {
        Logger.d("收到事件, messageEvent="+messageEvent);
        String cmd = messageEvent.getCommand();
        if (mMode == SocketMode.SERVER) {
            // TCP服务端启动成功
            if (cmd.equals(MessageEvent.CMD_S_TCP_SERVICE_SETUP)) {
                Intent tcpIntent = new Intent(mContext, UDPService.class);
                tcpIntent.putExtra("mode", mMode);
                mContext.bindService(tcpIntent, mUDPServiceConnection, Context.BIND_AUTO_CREATE);
            }
            // 有新的TCP客户端连接上了或者断开
            else if (cmd.equals(MessageEvent.CMD_S_TCP_CLIENT_CHANGE)) {
                //
                if (this.mServiceCallback != null) {
                    LinkedHashMap<String, SocketDevice> devicesList = mTCPService.getAllRegistedDevices();
                    this.mServiceCallback.onDevicesChange(devicesList);
                }
            }
            // 收到客户端发过来的消息
            else if(cmd.equals(MessageEvent.CMD_S_RECV_CLIENT_MESSAGE)) {
                String message = messageEvent.getParam(0);
                String tag = messageEvent.getParam(1);
                Logger.e("收到客户端 "+ tag+" 发过来的消息 message="+message);
                if (this.mServiceCallback != null) {
                    this.mServiceCallback.onReceive(message, tag);
                }
            }
            // 收到客户端发过来的图片
            else if(cmd.equals(MessageEvent.CMD_S_RECV_CLIENT_BITMAP)) {
                Bitmap bitmap = messageEvent.getBitmap();
                String tag = messageEvent.getParam(0);
                Logger.e("收到客户端 "+ tag+" 发过来的图片 bitmap="+bitmap);
                if (this.mServiceCallback != null) {
                    this.mServiceCallback.onReceive(bitmap, tag);
                }
            }
        }
        else if(mMode == SocketMode.CLIENT) {
            // 客户端收到服务端广播的端口信息
            if (cmd.equals(MessageEvent.MSG_BROADCAST_PORT)) {
                if (mTCPService != null) {
                    String tcpIp = messageEvent.getParam(0);
                    int tcpPort = Integer.valueOf(messageEvent.getParam(1));
                    String masterID = messageEvent.getParam(2);
                    mTCPService.startConnect(tcpIp, tcpPort, masterID);
                }
            }
            // 收到服务端发过来的消息
            else if(cmd.equals(MessageEvent.CMD_C_RECV_SERVICE_MESSAGE)) {
                String message = messageEvent.getParam(0);
                Logger.e("收到服务端发过来的消息 message="+message);
                if (this.mClientCallback != null) {
                    this.mClientCallback.onReceive(message);
                }
            }
            // 收到服务端发过来的图片
            else if(cmd.equals(MessageEvent.CMD_C_RECV_SERVICE_BITMAP)) {
                Bitmap bitmap = messageEvent.getBitmap();
                Logger.e("收到服务端发过来的图片 bitmap="+bitmap);
                if (this.mClientCallback != null) {
                    this.mClientCallback.onReceive(bitmap);
                }
            }
            // 断开连接，通知更新UI
            else if(cmd.equals(MessageEvent.CMD_C_CONNECT_CHANGE)){
                SocketStatus status = messageEvent.getStatus();
                Logger.e("客户端状态改变 status="+status);
                if (this.mClientCallback != null) {
                    this.mClientCallback.onStatusChange(status);
                }
            }
        }
    }


    /**
     * 客户端给服务端发送消息
     * @param message
     * @return
     */
    public boolean sendToService(String message) {
        if (mTCPService != null) {
            mTCPService.sendToService(message);
            return true;
        }
        return false;
    }

    /**
     * 服务端给客户端发送消息
     * @param message
     * @param tag
     * @return
     */
    public boolean sendToclient(String message, String tag){
        if (mTCPService != null) {
            mTCPService.sendToclient(message, tag);
            return true;
        }
        return false;
    }


    /**
     * 客户端给服务端发送消息
     * @return
     */
    public boolean sendToService(Bitmap bitmap) {
        if (mTCPService != null) {
            mTCPService.sendToService(bitmap);
            return true;
        }
        return false;
    }

    /**
     * 服务端给客户端发送消息
     * @param tag
     * @return
     */
    public boolean sendToclient(Bitmap bitmap, String tag){
        if (mTCPService != null) {
            mTCPService.sendToclient(bitmap, tag);
            return true;
        }
        return false;
    }

    private ServiceConnection mTCPServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mTCPService = ((TCPService.LocalBinder) service).getService();
            if (mTCPService != null) {
                mTCPService.startAccept();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mTCPService = null;
        }
    };

    private ServiceConnection mUDPServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mUDPService = ((UDPService.LocalBinder) service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mUDPService = null;
        }
    };


    public void setClientCallback(IClientCallback callback) {
        this.mClientCallback = callback;
    }

    public void setServiceCallback(IServiceCallback callback) {
        this.mServiceCallback = callback;
    }



}
