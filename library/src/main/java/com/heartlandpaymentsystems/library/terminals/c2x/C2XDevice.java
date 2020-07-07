package com.heartlandpaymentsystems.library.terminals.c2x;

import java.util.HashMap;
import java.util.HashSet;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.IntentFilter;

import com.heartlandpaymentsystems.library.terminals.enums.Environment;
import com.tsys.payments.library.connection.ConnectionListener;
import com.tsys.payments.library.domain.CardholderInteractionRequest;
import com.tsys.payments.library.domain.GatewayConfiguration;
import com.tsys.payments.library.domain.TerminalConfiguration;
import com.tsys.payments.library.domain.TerminalInfo;
import com.tsys.payments.library.domain.TransactionConfiguration;
import com.tsys.payments.library.domain.TransactionRequest;
import com.tsys.payments.library.domain.TransactionResponse;
import com.tsys.payments.library.enums.ConnectionType;
import com.tsys.payments.library.enums.CurrencyCode;
import com.tsys.payments.library.enums.TerminalAuthenticationCapability;
import com.tsys.payments.library.enums.TerminalInputCapability;
import com.tsys.payments.library.enums.TerminalOperatingEnvironment;
import com.tsys.payments.library.enums.TerminalOutputCapability;
import com.tsys.payments.library.enums.TerminalType;
import com.tsys.payments.library.enums.TransactionStatus;
import com.tsys.payments.library.exceptions.Error;
import com.tsys.payments.library.exceptions.InitializationException;
import com.tsys.payments.library.gateway.enums.GatewayType;
import com.tsys.payments.library.terminal.TerminalInfoListener;
import com.tsys.payments.library.utils.LibraryConfigHelper;
import com.tsys.payments.transaction.TransactionManager;

import com.heartlandpaymentsystems.library.terminals.ConnectionConfig;
import com.heartlandpaymentsystems.library.terminals.DeviceListener;
import com.heartlandpaymentsystems.library.terminals.IDevice;
import com.heartlandpaymentsystems.library.terminals.entities.TerminalResponse;
import com.heartlandpaymentsystems.library.terminals.TransactionListener;
import com.heartlandpaymentsystems.library.terminals.receivers.BluetoothDiscoveryListener;
import com.heartlandpaymentsystems.library.terminals.receivers.BluetoothReceiver;

public class C2XDevice implements IDevice {
    private Context applicationContext;
    private ConnectionConfig connectionConfig;
    private TransactionConfiguration transactionConfig;
    private TerminalConfiguration terminalConfig;
    private GatewayConfiguration gatewayConfig;
    private TransactionManager transactionManager;
    private TerminalInfo connectedTerminalInfo;
    private DeviceListener deviceListener;
    private TransactionListener transactionListener;
    private HashSet<BluetoothDevice> bluetoothDevices;
    private BluetoothReceiver bluetoothReceiver;

    public C2XDevice(Context applicationContext, ConnectionConfig connectionConfig) {
        this.applicationContext = applicationContext;
        this.connectionConfig = connectionConfig;

        LibraryConfigHelper.setDebugMode(connectionConfig.getEnvironment().equals(Environment.TEST));

        transactionConfig = new TransactionConfiguration();
        transactionConfig.setChipEnabled(true);
        transactionConfig.setContactlessEnabled(false);
        transactionConfig.setCurrencyCode(CurrencyCode.USD);
        transactionConfig.setMagStripeEnabled(true);

        terminalConfig = new TerminalConfiguration();
        terminalConfig.setTerminalType(TerminalType.BBPOS_C2X);
        terminalConfig.setCapability(TerminalInputCapability.MAGSTRIPE_ICC_KEYED_ENTRY_ONLY);
        terminalConfig.setOutputCapability(TerminalOutputCapability.PRINT_AND_DISPLAY);
        terminalConfig.setAuthenticationCapability(TerminalAuthenticationCapability.NO_CAPABILITY);
        terminalConfig.setOperatingEnvironment(TerminalOperatingEnvironment.ON_MERCHANT_PREMISES_ATTENDED);
//        terminalConfig.setTimeout(Long.getLong(connectionConfig.getTimeout()));

        HashMap<String, String> credentials = new HashMap<>();
//        credentials.put("secret_api_key", connectionConfig.getSecretApiKey());
        credentials.put("version_number", "3409");
        credentials.put("developer_id", "002914");
        credentials.put("user_name", connectionConfig.getUsername());
        credentials.put("license_id", connectionConfig.getLicenseId());
        credentials.put("site_id", connectionConfig.getSiteId());
        credentials.put("password", connectionConfig.getPassword());
        credentials.put("terminal_id", connectionConfig.getDeviceId());

        gatewayConfig = new GatewayConfiguration();
        gatewayConfig.setGatewayType(GatewayType.PORTICO);
        gatewayConfig.setCredentials(credentials);
    }

    public void setDeviceListener(DeviceListener deviceListener) {
        this.deviceListener = deviceListener;
    }

    public void setTransactionListener(TransactionListener transactionListener) {
        this.transactionListener = transactionListener;
    }

    public void initialize() {
        scan();
    }

    public void connect(BluetoothDevice device) {
        terminalConfig.setHost(device.getName());

        if (isTransactionManagerConnected()) {
            return;
        }

        transactionManager = TransactionManager.getInstance();

        if (transactionManager != null && terminalConfig != null) {
            ConnectionType[] connectionTypes =
                    transactionManager.getSupportedTerminalConnectionTypes(terminalConfig.getTerminalType());

            terminalConfig.setConnectionType(connectionTypes[0]);

            initializeTransactionManager();

            if (transactionManager.isInitialized()) {
                transactionManager.connect(new ConnectionListenerImpl());
            }
        }
    }

    public void disconnect() {
        if (isTransactionManagerConnected()) {
            transactionManager.disconnect();
        }
    }

    public void getDeviceInfo() {
        if (isTransactionManagerConnected()) {
            transactionManager.getDeviceInfo(new TerminalInfoListenerImpl());
        }
    }

    public void doTransaction(TransactionRequest transactionRequest) {
        if (transactionManager == null) {
            transactionManager = TransactionManager.getInstance();
        }

        if (!transactionManager.isInitialized()) {
            initializeTransactionManager();
        }

        transactionManager.startTransaction(transactionRequest, new TransactionListenerImpl());
    }

    protected void initializeTransactionManager() {
        try {
            transactionManager.initialize(
                    applicationContext,
                    terminalConfig,
                    transactionConfig,
                    gatewayConfig
            );
        } catch (InitializationException ex) {
            ex.printStackTrace();
        }
    }

    protected boolean isTransactionManagerConnected() {
        return transactionManager != null && transactionManager.isConnected();
    }

    protected void scan() {
        IntentFilter bluetoothFilter = new IntentFilter();
        bluetoothFilter.addAction(BluetoothDevice.ACTION_FOUND);
        bluetoothFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        bluetoothFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);

        if (bluetoothReceiver == null) {
            bluetoothReceiver = new BluetoothReceiver();
            bluetoothReceiver.setListener(new BluetoothListenerImpl());
        }

        if (applicationContext != null) {
            applicationContext.registerReceiver(bluetoothReceiver, bluetoothFilter);
        }
    }

    protected class ConnectionListenerImpl implements ConnectionListener {
        @Override
        public void onConnected(TerminalInfo terminalInfo) {
            connectedTerminalInfo = terminalInfo;

            if (deviceListener != null) {
                deviceListener.onConnected(
                        (com.heartlandpaymentsystems.library.terminals.entities.TerminalInfo) terminalInfo
                );
            }
        }

        @Override
        public void onError(Error error) {
            if (deviceListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                deviceListener.onError(err);
            }
        }
    }

    protected class BluetoothListenerImpl implements BluetoothDiscoveryListener {
        @Override
        public void onDiscoveryStarted() {
            bluetoothDevices = new HashSet<>();
        }

        @Override
        public void onDiscoveryFinished() {
            deviceListener.onBluetoothDeviceList(bluetoothDevices);
        }

        @Override
        public void onBluetoothDeviceFound(BluetoothDevice foundDevice) {
            if (foundDevice == null) {
                return;
            }

            if (foundDevice.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                return;
            }

            bluetoothDevices.add(foundDevice);
        }
    }

    protected class TerminalInfoListenerImpl implements TerminalInfoListener {
        @Override
        public void onTerminalInfoReceived(TerminalInfo terminalInfo) {
            if (deviceListener != null) {
                deviceListener.onTerminalInfoReceived(
                        (com.heartlandpaymentsystems.library.terminals.entities.TerminalInfo) terminalInfo
                );
            }
        }

        @Override
        public void onError(Error error) {
            if (deviceListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                deviceListener.onError(err);
            }
        }
    }

    protected class TransactionListenerImpl implements com.tsys.payments.library.transaction.TransactionListener {
        @Override
        public void onStatusUpdate(TransactionStatus transactionStatus) {
            if (transactionListener != null) {
                transactionListener.onStatusUpdate(
                        com.heartlandpaymentsystems.library.terminals.enums.TransactionStatus.fromVitalSdk(transactionStatus)
                );
            }
        }

        @Override
        public void onCardholderInteractionRequested(CardholderInteractionRequest cardholderInteractionRequest) {
            if (transactionListener != null) {
                transactionListener.onCardholderInteractionRequested(
                        (com.heartlandpaymentsystems.library.terminals.entities.CardholderInteractionRequest) cardholderInteractionRequest
                );
            }
        }

        @Override
        public void onTransactionComplete(TransactionResponse transactionResponse) {
            if (transactionListener != null) {
                transactionListener.onTransactionComplete(TerminalResponse.fromTransactionResponse(transactionResponse));
            }

        }

        @Override
        public void onError(Error error) {
            if (transactionListener != null) {
                java.lang.Error err = new java.lang.Error(error.getMessage());
                transactionListener.onError(err);
            }
        }
    }
}