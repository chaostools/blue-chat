package com.alexkang.btchatroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import com.alexkang.btchatroom.R;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.UUID;

public class HostActivity extends Activity {

    public static final int REQUEST_DISCOVERABLE = 1;
    public static final int PICK_IMAGE = 2;

    private EditText mMessage;
    private Button mAttachButton;
    private Button mSendButton;

    private String mChatRoomName;
    private BluetoothAdapter mBluetoothAdapter;
    private AcceptThread mAcceptThread;

    private ChatManager mChatManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chatroom);

        mMessage = (EditText) findViewById(R.id.message);
        mAttachButton = (Button) findViewById(R.id.attach);
        mSendButton = (Button) findViewById(R.id.send);
        mChatManager = new ChatManager(this, true);

        mAttachButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                uploadAttachment();
            }
        });

        mSendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendMessage();
            }
        });

        initializeBluetooth();
    }

    public void initializeBluetooth() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        final EditText nameInput = new EditText(this);
        nameInput.setSingleLine();
        nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Enter your ChatRoom name");
        builder.setView(nameInput);
        builder.setCancelable(false);
        builder.setPositiveButton("Submit", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int which) {
                mChatRoomName = nameInput.getText().toString();

                if (getActionBar() != null) {
                    getActionBar().setTitle(mChatRoomName);
                }

                Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                startActivityForResult(i, REQUEST_DISCOVERABLE);
            }
        });
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                finish();
            }
        });
        builder.show();
    }

    private void uploadAttachment() {
        Intent i = new Intent();
        i.setType("image/*");
        i.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(i, "Select Picture"), PICK_IMAGE);
    }

    private void sendMessage() {
        byte[] byteArray;

        if (mMessage.getText().toString().length() == 0) {
            return;
        }

        try {
            byte[] messageBytes = mMessage.getText().toString().getBytes();

            ByteArrayOutputStream output = new ByteArrayOutputStream(messageBytes.length + 1);
            output.write(ChatManager.MESSAGE_RECEIVE);
            output.write(mBluetoothAdapter.getName().length());
            output.write(mBluetoothAdapter.getName().getBytes());
            output.write(messageBytes);

            byteArray = output.toByteArray();
        } catch (Exception e) {
            return;
        }

        mChatManager.write(byteArray);
        mMessage.setText("");
    }

    private void sendImage(Bitmap bitmap) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream imageArray = new ByteArrayOutputStream();

            output.write(ChatManager.MESSAGE_RECEIVE_IMAGE);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 25, imageArray);
            output.write(imageArray.toByteArray());

            byte[] byteArray = output.toByteArray();
            Toast.makeText(this, byteArray.length + "", Toast.LENGTH_SHORT).show();

            mChatManager.write(byteArray);
        } catch (Exception e) {}
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != RESULT_CANCELED && requestCode == REQUEST_DISCOVERABLE) {
            mAcceptThread = new AcceptThread();
            mAcceptThread.start();
            Toast.makeText(this, "Searching for users...", Toast.LENGTH_SHORT).show();
        } else if (resultCode == RESULT_OK && requestCode == PICK_IMAGE) {
            Uri image = data.getData();
            String[] filePathColumn = { MediaStore.Images.Media.DATA };
            Cursor cursor = getContentResolver().query(image, filePathColumn, null, null, null);
            cursor.moveToFirst();
            int columnIndex = cursor.getColumnIndex(filePathColumn[0]);
            String picturePath = cursor.getString(columnIndex);
            cursor.close();

            sendImage(BitmapFactory.decodeFile(picturePath));
        } else {
            Toast.makeText(this, "Something went wrong, now exiting.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void onStop() {
        super.onStop();

    }

    private void manageSocket(BluetoothSocket socket) {
        Toast.makeText(this, socket.getRemoteDevice().getName() + " connected", Toast.LENGTH_SHORT).show();
        mChatManager.startConnection(socket);
        byte[] byteArray;

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(mChatRoomName.length() + 1);
            output.write(ChatManager.MESSAGE_NAME);
            output.write(mChatRoomName.getBytes());
            byteArray = output.toByteArray();
        } catch (IOException e) {
            return;
        }

        mChatManager.writeName(byteArray);
    }

    private class AcceptThread extends Thread {

        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            BluetoothServerSocket tmp = null;

            try {
                tmp = mBluetoothAdapter.
                        listenUsingRfcommWithServiceRecord(
                                mChatRoomName, java.util.UUID.fromString(MainActivity.UUID)
                        );
            } catch (IOException e) {}

            mmServerSocket = tmp;
        }

        public void run() {
            while (true) {
                final BluetoothSocket socket;

                try {
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    break;
                }

                if (socket != null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            manageSocket(socket);
                        }
                    });
                }
            }
        }

        public void cancel() {
            try {
                mmServerSocket.close();
            } catch (IOException e) {}
        }

    }

}
