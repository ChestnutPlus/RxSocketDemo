package com.hy.RxSocketDemo;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import chestnut.utils.LogUtils;
import rx.Observable;
import rx.Subscriber;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subjects.SerializedSubject;
import rx.subjects.Subject;

/**
 * <pre>
 *     author: 栗子酱
 *     blog  : http://www.jianshu.com/u/a0206b5f4526
 *     time  : 2017年1月30日17:00:21
 *     desc  :  RxSocket
 *     thanks To:
 *     dependent on:
 *     update log:
 *          1.  2016年12月20日16:35:31     修复Socket被远程关闭后，不断接收关闭消息的bug     by 栗子酱
 *          2.  2017年1月28日22:38:32      修复：Write中，返回值为 0 的时候，是正常的，只是写了 长度 为0的包。     by  栗子酱
 *          3.  2017年1月29日13:52:35      增加：disConnect()
 * </pre>
 */

public class RxSocket {

    /*  常量
    * */
    private String TAG = "RxSocket";
    private boolean OpenLog = false;
    private long WRITE_TIME_OUT = 3000;
    private long CONNECT_TIME_OUT = 3000;

    /*  单例
    * */
    private Subject<Object,byte[]> readSubject;
    private Subject<Object,SocketStatus> connectStatus;
    private static volatile RxSocket defaultInstance;
    private RxSocket() {
        readSubject = new SerializedSubject(PublishSubject.create());
        connectStatus = new SerializedSubject(PublishSubject.create());
    }
    public static RxSocket getInstance() {
        RxSocket rxSocket = defaultInstance;
        if (defaultInstance == null) {
            synchronized (chestnut.RxSocket.RxSocket.class) {
                rxSocket = defaultInstance;
                if (defaultInstance == null) {
                    rxSocket = new RxSocket();
                    defaultInstance = rxSocket;
                }
            }
        }
        return rxSocket;
    }

    /*  变量
    * */
    private SocketStatus socketStatus = SocketStatus.DIS_CONNECT;
    private Selector selector = null;
    private SocketChannel socketChannel = null;
    private SelectionKey selectionKey = null;
    private ReadThread readThread = null;
    private boolean isReadThreadAlive = true;
    private SocketReconnectCallback socketReconnectCallback = null;

    /*  方法
    * */
    /**
     * 监听Socket的状态
     * @return Rx SocketStatus 状态
     */
    public Observable<SocketStatus> socketStatusListener () {
        return connectStatus;
    }

    /**
     * 建立Socket连接，只是尝试建立一次
     * @param ip    IP or 域名
     * @param port  端口
     * @return  Rx true or false
     */
    public Observable<Boolean> connectRx(String ip, int port) {
        return Observable
                .create(new Observable.OnSubscribe<Boolean>() {
                    @Override
                    public void call(Subscriber<? super Boolean> subscriber) {

                        LogUtils.i(OpenLog,TAG,"connectRx:"+"status:"+socketStatus.name());

                        //正在连接
                        if (socketStatus == SocketStatus.CONNECTING) {
                            subscriber.onNext(false);
                            subscriber.onCompleted();
                            return;
                        }

                        //未连接 | 已经连接，关闭Socket
                        socketStatus = SocketStatus.DIS_CONNECT;
                        isReadThreadAlive = false;
                        readThread = null;
                        if (selector!=null)
                            try {
                                selector.close();
                            } catch (Exception e) {
                                LogUtils.i(OpenLog,TAG,"selector.close");
                            }
                        if (selectionKey!=null)
                            try {
                                selectionKey.cancel();
                            } catch (Exception e) {
                                LogUtils.i(OpenLog,TAG,"selectionKey.cancel");
                            }
                        if (socketChannel!=null)
                            try {
                                socketChannel.close();
                            } catch (Exception e) {
                                LogUtils.i(OpenLog,TAG,"socketChannel.close");
                            }

                        //重启Socket
                        isReadThreadAlive = true;
                        readThread = new ReadThread(ip,port);
                        readThread.start();
                        socketReconnectCallback = new SocketReconnectCallback() {
                            @Override
                            public void onSuccess() {
                                LogUtils.i(OpenLog,TAG,"connectRx:"+"CONNECTED");
                                socketStatus = SocketStatus.CONNECTED;
                                subscriber.onNext(true);
                                subscriber.onCompleted();
                            }

                            @Override
                            public void onFail(String msg) {
                                LogUtils.i(OpenLog,TAG,"connectRx:"+msg);
                                subscriber.onNext(false);
                                subscriber.onCompleted();
                            }
                        };
                    }
                })
                .subscribeOn(Schedulers.newThread())
                .map(aBoolean -> {
                    socketReconnectCallback = null;
                    return aBoolean;
                })
                .timeout(CONNECT_TIME_OUT, TimeUnit.MILLISECONDS, Observable.just(false));
    }

    /**
     * 断开当前的Socket
     *  还能再继续连接
     * @return Rx true or false
     */
    public Observable<Boolean> disConnect() {
        return Observable.create(new Observable.OnSubscribe<Boolean>() {
            @Override
            public void call(Subscriber<? super Boolean> subscriber) {
                try {
                    if (socketStatus == SocketStatus.DIS_CONNECT) {
                        subscriber.onNext(true);
                        subscriber.onCompleted();
                    }
                    else {
                        socketStatus = SocketStatus.DIS_CONNECT;
                        isReadThreadAlive = false;
                        readThread = null;
                        if (selector!=null)
                            try {
                                selector.close();
                            } catch (Exception e) {
                                LogUtils.i(OpenLog,TAG,"selector.close");
                            }
                        if (selectionKey!=null)
                            try {
                                selectionKey.cancel();
                            } catch (Exception e) {
                                LogUtils.i(OpenLog,TAG,"selectionKey.cancel");
                            }
                        if (socketChannel!=null)
                            try {
                                socketChannel.close();
                            } catch (Exception e) {
                                LogUtils.i(OpenLog,TAG,"socketChannel.close");
                            }
                        subscriber.onNext(true);
                        subscriber.onCompleted();
                    }
                } catch (Exception e) {
                    subscriber.onNext(false);
                    subscriber.onCompleted();
                }
            }
        });
    }

    /**
     * 读取Socket的消息
     * @return  Rx error 或者 有数据
     */
    public Observable<byte[]> read() {
        return readSubject;
    }

    /**
     * 向Socket写消息
     * @param buffer    数据包
     * @return  Rx true or false
     */
    public Observable<Boolean> write(ByteBuffer buffer) {
        return Observable
                .create(new Observable.OnSubscribe<Boolean>() {
                    @Override
                    public void call(Subscriber<? super Boolean> subscriber) {
                        if (socketStatus != SocketStatus.CONNECTED) {
                            LogUtils.i(OpenLog, TAG, "write." + "SocketStatus.DISCONNECTED");
                            subscriber.onNext(false);
                            subscriber.onCompleted();
                        }
                        else {
                            if (socketChannel!=null && socketChannel.isConnected()) {
                                try {
                                    int result = socketChannel.write(buffer);
                                    if (result<0) {
                                        LogUtils.i(OpenLog, TAG, "write." + "发送出错");
                                        subscriber.onNext(false);
                                        subscriber.onCompleted();
                                    }
                                    else {
                                        LogUtils.i(OpenLog, TAG, "write." + "success!");
                                        subscriber.onNext(true);
                                        subscriber.onCompleted();
                                    }
                                } catch (Exception e) {
                                    LogUtils.i(OpenLog,TAG,"write."+e.getMessage());
                                    subscriber.onNext(false);
                                    subscriber.onCompleted();
                                }
                            }
                            else {
                                LogUtils.i(OpenLog,TAG,"write."+"close");
                                subscriber.onNext(false);
                                subscriber.onCompleted();
                            }
                        }
                    }
                })
                .subscribeOn(Schedulers.newThread())
                .timeout(WRITE_TIME_OUT, TimeUnit.MILLISECONDS, Observable.just(false));
    }

    /**
     * 获取Socket的链接状态
     * @return  状态
     */
    public SocketStatus getSocketStatus() {
        return socketStatus;
    }

    /*  类 && 枚举 && 接口
        * */
    private class ReadThread extends Thread {
        private String ip;
        private int port;
        ReadThread(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }
        @Override
        public void run() {
            LogUtils.i(OpenLog,TAG,"ReadThread:"+"start");
            while (isReadThreadAlive) {
                //连接
                if (socketStatus == SocketStatus.DIS_CONNECT) {
                    try {
                        if (selectionKey != null) selectionKey.cancel();
                        socketChannel = SocketChannel.open();
                        socketChannel.configureBlocking(false);
                        selector = Selector.open();
                        socketChannel.connect(new InetSocketAddress(ip, port));
                        selectionKey = socketChannel.register(selector, SelectionKey.OP_CONNECT);
                        socketStatus = SocketStatus.CONNECTING;
                        connectStatus.onNext(SocketStatus.CONNECTING);
                    } catch (Exception e) {
                        isReadThreadAlive = false;
                        socketStatus = SocketStatus.DIS_CONNECT;
                        connectStatus.onNext(SocketStatus.DIS_CONNECT);
                        LogUtils.e(OpenLog, TAG, "ReadThread:init:" + e.getMessage());
                        if (socketReconnectCallback!=null)
                            socketReconnectCallback.onFail("SocketConnectFail1");
                    }
                }
                //读取
                else if (socketStatus == SocketStatus.CONNECTING || socketStatus  == SocketStatus.CONNECTED) {
                    try {
                        selector.select();
                        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                        while (it.hasNext()) {
                            SelectionKey key = it.next();
                            if (key.isConnectable()) {
                                if (socketChannel.isConnectionPending()) {
                                    try {
                                        socketChannel.finishConnect();
                                        socketStatus = SocketStatus.CONNECTED;
                                        connectStatus.onNext(SocketStatus.CONNECTED);
                                        socketChannel.configureBlocking(false);
                                        socketChannel.register(selector, SelectionKey.OP_READ);
                                        if (socketReconnectCallback!=null)
                                            socketReconnectCallback.onSuccess();
                                    } catch (Exception e) {
                                        isReadThreadAlive = false;
                                        socketStatus = SocketStatus.DIS_CONNECT;
                                        connectStatus.onNext(SocketStatus.DIS_CONNECT);
                                        LogUtils.e(OpenLog, TAG, "ReadThread:finish:" + e.getMessage());
                                        if (socketReconnectCallback!=null)
                                            socketReconnectCallback.onFail("SocketConnectFail2");
                                    }
                                }
                            } else if (key.isReadable()) {
                                ByteBuffer buf = ByteBuffer.allocate(10000);
                                int length = socketChannel.read(buf);
                                if (length <= 0) {
                                    LogUtils.e(OpenLog, TAG, "服务器主动断开链接！");
                                    isReadThreadAlive = false;
                                    socketStatus = SocketStatus.DIS_CONNECT;
                                    connectStatus.onNext(SocketStatus.DIS_CONNECT);
                                    if (socketReconnectCallback!=null)
                                        socketReconnectCallback.onFail("SocketConnectFail3");
                                } else {
                                    LogUtils.i(OpenLog, TAG, "readSubject:msg！"+ "length:" + length);
                                    byte[] bytes = new byte[length];
                                    for (int i = 0; i < length; i++) {
                                        bytes[i] = buf.get(i);
                                    }
                                    readSubject.onNext(bytes);
                                }
                            }
                        }
                        it.remove();
                    } catch (Exception e) {
                        isReadThreadAlive = false;
                        socketStatus = SocketStatus.DIS_CONNECT;
                        connectStatus.onNext(SocketStatus.DIS_CONNECT);
                        LogUtils.e(OpenLog, TAG, "ReadThread:read:" + e.getMessage());
                        if (socketReconnectCallback!=null)
                            socketReconnectCallback.onFail("SocketConnectFail4");
                    }
                }
            }
        }
    }

    public enum SocketStatus {
        DIS_CONNECT,
        CONNECTING,
        CONNECTED,
    }

    private interface SocketReconnectCallback {
        void onSuccess();
        void onFail(String msg);
    }
}
