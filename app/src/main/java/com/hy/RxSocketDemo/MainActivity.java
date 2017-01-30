package com.hy.RxSocketDemo;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.nio.ByteBuffer;

import chestnut.ui.Toastc;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

public class MainActivity extends AppCompatActivity {

    private Toastc toast = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnConnect = (Button) findViewById(R.id.btnConnect);
        Button btnDisConnect = (Button) findViewById(R.id.btnDisConnect);
        Button btnWrite = (Button) findViewById(R.id.btnWrite);

        toast = new Toastc(this, Toast.LENGTH_SHORT);

        btnConnect.setOnClickListener(clickListener);
        btnDisConnect.setOnClickListener(clickListener);
        btnWrite.setOnClickListener(clickListener);

        //监听Socket的收包
        RxSocket.getInstance().read()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        bytes -> {
                            //这里，应该去处理这个消息
                            LogToast("get Msg : (size)" + bytes.length);
                        },
                        throwable -> {

                        },
                        ()->{

                        }
                );

        //监听Socket的连接状态
        RxSocket.getInstance().socketStatusListener()
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(socketStatus -> {
                    LogToast("socketStatus:" + socketStatus.name());
                });
    }

    private View.OnClickListener clickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            switch (view.getId()) {
                case R.id.btnConnect:
                    //进行连接Socket：这里使用的是，在本地上开启了一个服务器。
                    RxSocket.getInstance().connectRx("192.168.1.107",8888)
                            .subscribe(
                                    aBoolean -> {
                                        LogToast("RxSocket Connect "+aBoolean);
                                    },
                                    throwable -> {
                                        LogToast("RxSocket Connect Error : "+throwable.getMessage());
                                    },
                                    () -> {

                                    }
                            );
                    break;
                case R.id.btnDisConnect:
                    //断开连接
                    RxSocket.getInstance().disConnect()
                            .subscribe(
                                    aBoolean -> {
                                        LogToast("RxSocket DisConnect "+aBoolean);
                                    },
                                    throwable -> {
                                        LogToast("RxSocket DisConnect Error : "+throwable.getMessage());
                                    },
                                    () -> {

                                    }
                            );
                    break;
                case R.id.btnWrite:
                    //向RxSocket写数据
                    //示例数据：
                    byte[] test = {1,2,9,3};
                    ByteBuffer byteBuffer = ByteBuffer.wrap(test);
                    RxSocket.getInstance().write(byteBuffer)
                            .subscribe(
                                    aBoolean -> {
                                        LogToast("RxSocket write "+aBoolean);
                                    },
                                    throwable -> {
                                        LogToast("RxSocket write Error : "+throwable.getMessage());
                                    },
                                    () -> {

                                    }
                            );
                    break;
            }
        }
    };

    private void LogToast(String msg) {
        if (msg!=null && msg.length()>0 && toast!=null)
            toast.setText(msg).show();
    }
}
