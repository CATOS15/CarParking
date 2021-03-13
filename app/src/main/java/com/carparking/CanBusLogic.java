package com.carparking;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

public class CanBusLogic {
    BluetoothAdapter mBluetoothAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    Thread readThread, writeThread;
    byte[] readBuffer;
    int readBufferPosition;

    volatile String prev_data_read = "";
    volatile boolean stopReadWorker = false;
    volatile boolean stopWriteWorker = false;

    String id;
    TextView textView;

    void startBT(Handler handler) throws Exception {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if(mBluetoothAdapter == null)
        {
            throw new Exception("Ingen bluetooth enhed fundet!");
        }

        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if(pairedDevices.size() > 0)
        {
            for(BluetoothDevice device : pairedDevices)
            {
                String deviceName = device.getName();
                if(deviceName.equals("OBDLink MX"))
                {
                    mmDevice = device;
                    break;
                }
            }
        }

        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID
        mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
        mmSocket.connect();
        mmOutputStream = mmSocket.getOutputStream();
        mmInputStream = mmSocket.getInputStream();

        beginListenForData(handler);
    }

    @SuppressLint("SetTextI18n")
    void beginListenForData(Handler handler)
    {
        final byte delimiter = 13;

        readBufferPosition = 0;
        readBuffer = new byte[1024];
        readThread = new Thread(() -> {
            while(!Thread.currentThread().isInterrupted() && !stopReadWorker)
            {
                try
                {
                    int bytesAvailable = mmInputStream.available();
                    if(bytesAvailable > 0)
                    {
                        byte[] packetBytes = new byte[bytesAvailable];
                        mmInputStream.read(packetBytes);
                        for(int i=0;i<bytesAvailable;i++)
                        {
                            byte b = packetBytes[i];
                            if(b == delimiter)
                            {
                                byte[] encodedBytes = new byte[readBufferPosition];
                                System.arraycopy(readBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                final String data = new String(encodedBytes, StandardCharsets.US_ASCII);
                                readBufferPosition = 0;

                                handler.post(() -> {
                                    if(data.equals(prev_data_read)) return;
                                    prev_data_read = data;

                                    System.out.println(data);
                                    if(stopWriteWorker && this.id != null && data != null) {
                                        if (this.id.equals("346") && data.length() > 10) {
                                            try{
                                                int brakeValue = Integer.parseInt(data.substring(8, 9));
                                                if (brakeValue == 2) {
                                                    this.textView.setText("Ja");
                                                } else {
                                                    this.textView.setText("Nej");
                                                }
                                            } catch (Exception e){
                                                System.out.println("Kunne ikke passes til int: " + data.substring(8, 9));
                                            }
                                        }
                                        if (this.id.equals("374") && data.length() > 10) {
                                            int battery = Integer.parseInt(data.substring(2, 4), 16) - 110;
                                            this.textView.setText(battery + "%");
                                        }
                                    }
                                });
                            }
                            else
                            {
                                readBuffer[readBufferPosition++] = b;
                            }
                        }
                    }
                }
                catch (IOException ex)
                {
                    stopReadWorker = true;
                }
            }
        });

        readThread.start();
    }

    void listenCanBusData() throws IOException {
        this.stopWriteWorker = false;
        writeThread = new Thread(() -> {
            while(!Thread.currentThread().isInterrupted() && !stopWriteWorker)
            {
                try {
                    this.sendData("atsp6");
                    Thread.sleep(1000);
                    this.sendData("ate0");
                    Thread.sleep(1000);
                    this.sendData("atcaf0");
                    Thread.sleep(1000);
                    this.sendData("atS0");
                    stopWriteWorker = true;
                }
                catch(Exception e){
                    e.printStackTrace();
                }
            }
        });
        writeThread.start();
    }

    void switchCanBusData(String id, TextView textView) throws IOException {
        this.id = id;
        this.textView = textView;
        this.stopWriteWorker = false;
        if(writeThread != null) {
            writeThread.interrupt();
            writeThread = null;
        }
        writeThread = new Thread(() -> {
            while(!Thread.currentThread().isInterrupted() && !stopWriteWorker)
            {
                try {
                    this.sendData("atcra " + id);
                    Thread.sleep(1000);
                    this.sendData("atma");
                    stopWriteWorker = true;
                }
                catch(Exception e){
                    e.printStackTrace();
                }
            }
        });
        writeThread.start();
    }

    boolean isSwitchReady(){
        return stopWriteWorker;
    }

    void sendData(String msg) throws IOException
    {
        msg += "\r";
        mmOutputStream.write(msg.getBytes());
    }
}