package com.luxvelocitas.sensor;

import java.nio.ByteBuffer;

import org.slf4j.Logger;

import com.aau.bluetooth.BluetoothClient;
import com.aau.bluetooth.BluetoothException;
import com.aau.bluetooth.IBluetooth;
import com.aau.bluetooth.IBluetoothDevice;
import com.aau.bluetooth.IBluetoothReadyStateListener;
import com.aau.bluetooth.data.IDataListener;
import com.aau.bluetooth.data.SimpleDataListener;
import com.luxvelocitas.datautils.DataBundle;
import com.luxvelocitas.tinyevent.SimpleTinyEventDispatcher;


/**
 * Interface to a Polar Bluetooth heart rate monitor
 *
 * @author Konrad Markus <konker@luxvelocitas.com>
 *
 * NOTES:
 *  Polar Bluetooth Wearlink packet example;
 *   Hdr Len Chk Seq Status HeartRate RRInterval_16-bits
 *    FE  08  F7  06   F1      48          03 64
 *   where;
 *      Hdr always = 254 (0xFE),
 *      Chk = 255 - Len
 *      Seq range 0 to 15
 *      Status = Upper nibble may be battery voltage
 *               bit 0 is Beat Detection flag.
 */
public class PolarBtHrm extends SimpleTinyEventDispatcher<PolarBtHrmEventType, DataBundle> {
    public static final String KEY_STATUS = "status";
    public static final String KEY_BATTERY_LEVEL = "batteryLevel";
    public static final String KEY_HEART_RATE = "heartRate";
    public static final int NO_HEART_RATE = -1;

    private static final byte HEADER = (byte)254;
    private static final int PACKET_LENGTH = 8;
    private static final int INDEX_STATUS = 4;
    private static final int INDEX_HEART_RATE = 5;

    protected static final int DEFAULT_NUM_RETRIES = 3;
    protected static final int DEFAULT_RETRY_INTERVAL_MS = 3000;

    protected int mStatus;
    protected int mBatteryLevel;
    protected int mHeartRate;

    protected Logger mLogger;
    protected IBluetooth mBluetooth;
    protected String mBluetoothName;
    protected BluetoothClient mBluetoothClient;
    protected final IDataListener mDataListener;

    protected final DataBundle mDataBundle;

    public PolarBtHrm(Logger logger, IBluetooth bluetooth, String bluetoothName) {
        mBatteryLevel = 100;
        mStatus = 0;
        mHeartRate = NO_HEART_RATE;

        mLogger = logger;
        mBluetooth = bluetooth;
        mBluetoothName = bluetoothName;

        mDataBundle = new DataBundle();

        mDataListener = new SimpleDataListener() {
            private final ByteBuffer mBuffer = ByteBuffer.allocateDirect(32);

            private void handlePacket() {
                // Move the position to the beginning of the packet
                mBuffer.flip();

                // Extract the data
                mStatus = mBuffer.get(INDEX_STATUS);

                // Battery level is the high nibble of status
                mBatteryLevel =  (mStatus >> 4) & 0x0F;
                mHeartRate = (mBuffer.get(INDEX_HEART_RATE) & 0xFF);

                // Move the position to the end of the packet
                mBuffer.position(PACKET_LENGTH);

                // Shift the remaining data to the beginning of the buffer
                mBuffer.compact();

                mDataBundle.put(KEY_STATUS, mStatus);
                mDataBundle.put(KEY_BATTERY_LEVEL, mBatteryLevel);
                mDataBundle.put(KEY_HEART_RATE, mHeartRate);

                // Fire an event
                PolarBtHrm.this.notify(PolarBtHrmEventType.HEAR_RATE, mDataBundle);
            }

            @Override
            public void onData(BluetoothClient connection, final byte[] data, final int len) {
                // If we find a delimiter, clear the buffer and start again
                if (data[0] == HEADER) {
                    mBuffer.clear();
                }

                // Push the data to the end of the buffer
                mBuffer.put(data, 0, len);

                // Handle as many packets as we can
                while (mBuffer.position() > PACKET_LENGTH) {
                    // Handle a packet
                    handlePacket();
                }
            }
        };
    }

    public void connect(IBluetoothReadyStateListener bluetoothReadyStateListener) {
        // Find the device from already paired devices
        IBluetoothDevice device = mBluetooth.getPairedDeviceByName(mBluetoothName);

        if (device == null) {
            mLogger.error("Could not find paired device: " + mBluetoothName);
            bluetoothReadyStateListener.onError(null, new BluetoothException("Could not find paired device: " + mBluetoothName));
            return;
        }
        mBluetoothClient = new BluetoothClient(mLogger,
                                               device,
                                               IBluetooth.RFCOMM_UUID,
                                               DEFAULT_NUM_RETRIES,
                                               DEFAULT_RETRY_INTERVAL_MS);

        // Add listeners to the connection
        mBluetoothClient
            .addReadyStateListener(bluetoothReadyStateListener)
            .addDataListener(mDataListener)
            .connect();
    }

    public void close() {
        if (mBluetoothClient != null) {
            mBluetoothClient.close();
        }
    }

    public int getHeartRate() {
        return mHeartRate;
    }

    public int getBatteryLevel() {
        return mBatteryLevel;
    }

    public int getStatus() {
        return mStatus;
    }
}
