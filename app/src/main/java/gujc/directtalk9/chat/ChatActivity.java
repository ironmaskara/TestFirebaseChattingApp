package gujc.directtalk9.chat;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import gujc.directtalk9.R;
import gujc.directtalk9.common.Util9;
import gujc.directtalk9.model.ChatModel;
import gujc.directtalk9.model.NotificationModel;
import gujc.directtalk9.model.UserModel;

import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.transition.Transition;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChatActivity extends AppCompatActivity {
    private static final int PICK_FROM_ALBUM = 1;
    private static final int PICK_FROM_FILE = 2;

    private Button sendBtn;
    private EditText msg_input;
    private RecyclerView recyclerView;

    private SimpleDateFormat dateFormatDay = new SimpleDateFormat("yyyy-MM-dd");
    private SimpleDateFormat dateFormatHour = new SimpleDateFormat("aa hh:mm");
    private String roomID;
    private String myUid;
    private Map<String, UserModel> userList = new HashMap<>();

    private DatabaseReference databaseReference;
    private ValueEventListener valueEventListener;
    private DatabaseReference db=null;
    StorageReference storageReference;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        db = FirebaseDatabase.getInstance().getReference();
        storageReference  = FirebaseStorage.getInstance().getReference();

        dateFormatDay.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        dateFormatHour.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));

        myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String toUid = getIntent().getStringExtra("toUid");
        String param_room_id = getIntent().getStringExtra("roomID");

        recyclerView = findViewById(R.id.recyclerView);
        msg_input = findViewById(R.id.msg_input);
        sendBtn = findViewById(R.id.sendBtn);
        sendBtn.setOnClickListener(sendBtnClickListener);

        findViewById(R.id.imageBtn).setOnClickListener(imageBtnClickListener);
        findViewById(R.id.fileBtn).setOnClickListener(fileBtnClickListener);

        /*
         two user: roomid or uid talking
         multi user: roomid
         */
        if (!"".equals(toUid) && toUid!=null) {                     // find existing room for two user
            findChatRoom(toUid);
        } else
        if (!"".equals(param_room_id) && param_room_id!=null) { // existing room (multi user)
            setChatRoom(param_room_id);
        };

        if (roomID==null) {                                                     // new room for two user
            getUserInfoFromServer(myUid);
            getUserInfoFromServer(toUid);
        };
    }

    void getUserInfoFromServer(String id){
        db.child("users").child(id).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                UserModel userModel = dataSnapshot.getValue(UserModel.class);
                userList.put(userModel.getUid(), userModel);
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {
            }
        });
    }

    void findChatRoom(final String toUid){
        db.child("rooms").orderByChild("users/"+myUid).equalTo("i").addListenerForSingleValueEvent(new ValueEventListener(){
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                for (DataSnapshot item: dataSnapshot.getChildren()) {
                    Map<String, String> users = (Map<String, String>) item.child("users").getValue();
                    if (users.size()==2 && users.containsKey(toUid)) {
                        setChatRoom(item.getKey());
                        break;
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {   }
        });
    }

    void setChatRoom(String rid) {
        roomID = rid;
        db.child("rooms").child(roomID).child("users").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                for (DataSnapshot item: dataSnapshot.getChildren()) {
                    getUserInfoFromServer(item.getKey());
                }
                recyclerView.setLayoutManager(new LinearLayoutManager(ChatActivity.this));
                recyclerView.setAdapter(new RecyclerViewAdapter());
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {}
        });
    }

    Button.OnClickListener sendBtnClickListener = new View.OnClickListener() {
        public void onClick(View view) {
            String msg = msg_input.getText().toString();
            sendMessage(msg, "0");
            msg_input.setText("");
        }
    };

    private void sendMessage(String msg, String msgtype) {
        sendBtn.setEnabled(false);

        if (roomID==null) {             // two user
            ChatModel chatModel = new ChatModel();
            for( String key : userList.keySet() ){
                chatModel.users.put(key, "i");
            }
            roomID = db.child("rooms").push().getKey();
            db.child("rooms/"+roomID).setValue(chatModel);
            recyclerView.setLayoutManager(new LinearLayoutManager(ChatActivity.this));
            recyclerView.setAdapter(new RecyclerViewAdapter());
        }

        ChatModel.Message messages = new ChatModel.Message();
        messages.uid = myUid;
        messages.msg = msg;
        messages.msgtype=msgtype;
        messages.timestamp = ServerValue.TIMESTAMP;
        db.child("rooms").child(roomID).child("lastmessage").setValue(messages);    // save last message
        // save message
        messages.readUsers.put(myUid, true);
        db.child("rooms").child(roomID).child("messages").push().setValue(messages).addOnCompleteListener(new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull Task<Void> task) {
                //sendGCM();
                sendBtn.setEnabled(true);
            }
        });
        // inc unread message count
        db.child("rooms").child(roomID).child("users").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                for (final DataSnapshot item : dataSnapshot.getChildren()) {
                    final String uid = item.getKey();
                    if (!myUid.equals(item.getKey())) {
                        db.child("rooms").child(roomID).child("unread").child(item.getKey()).addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(DataSnapshot dataSnapshot) {
                                Integer cnt = dataSnapshot.getValue(Integer.class);
                                if (cnt==null) cnt=0;
                                db.child("rooms").child(roomID).child("unread").child(uid).setValue(cnt+1);
                            }

                            @Override
                            public void onCancelled(DatabaseError databaseError) {

                            }
                        });
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) { }
        });
    };

    void sendGCM(){
         Gson gson = new Gson();
         NotificationModel notificationModel = new NotificationModel();
         notificationModel.notification.title = userList.get(myUid).getUsernm();
         notificationModel.notification.body = msg_input.getText().toString();
         notificationModel.data.title = userList.get(myUid).getUsernm();
         notificationModel.data.body = msg_input.getText().toString();

         for ( Map.Entry<String, UserModel> elem : userList.entrySet() ){
             if (myUid.equals(elem.getValue().getUid())) continue;
             notificationModel.to = elem.getValue().getToken();
             RequestBody requestBody = RequestBody.create(MediaType.parse("application/json; charset=utf8"), gson.toJson(notificationModel));
             Request request = new Request.Builder()
                    .header("Content-Type", "application/json")
                    .addHeader("Authorization", "key=")
                    .url("https://fcm.googleapis.com/fcm/send")
                    .post(requestBody)
                    .build();

             OkHttpClient okHttpClient = new OkHttpClient();
             okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {                }
                @Override
                public void onResponse(Call call, Response response) throws IOException {                }
             });
         }
    }

    Button.OnClickListener imageBtnClickListener = new View.OnClickListener() {
        public void onClick(final View view) {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setType(MediaStore.Images.Media.CONTENT_TYPE);
            startActivityForResult(intent, PICK_FROM_ALBUM);
        }
    };
    Button.OnClickListener fileBtnClickListener = new View.OnClickListener() {
        public void onClick(final View view) {
            Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            intent.setType("*/*");
            intent.setAction(Intent.ACTION_GET_CONTENT);
            startActivityForResult(Intent.createChooser(intent, "Select File"), PICK_FROM_FILE);
        }
    };

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode!= this.RESULT_OK) { return;}
        Uri fileUri = data.getData();
        final String filename = Util9.getUniqueValue();

        if (requestCode ==PICK_FROM_FILE) {
            //File file= new File(fileUri.getPath());
            final ChatModel.FileInfo fileinfo  = getFileDetailFromUri(getApplicationContext(), fileUri);//file.getName();

            storageReference.child("files/"+filename).putFile(fileUri).addOnCompleteListener(new OnCompleteListener<UploadTask.TaskSnapshot>() {
                @Override
                public void onComplete(@NonNull Task<UploadTask.TaskSnapshot> task) {
                    sendMessage(filename, "2");
                    db.child("rooms").child(roomID).child("files").child(filename).setValue(fileinfo);    // save file information
                }
            });
            return;
        }
        if (requestCode ==PICK_FROM_ALBUM) { return;}

        // upload image
        storageReference.child("files/"+filename).putFile(fileUri).addOnCompleteListener(new OnCompleteListener<UploadTask.TaskSnapshot>() {
            @Override
            public void onComplete(@NonNull Task<UploadTask.TaskSnapshot> task) {
                sendMessage(filename, "1");
            }
        });
        // small image
        Glide.with(getApplicationContext())
                .asBitmap()
                .load(fileUri)
                .apply(new RequestOptions().override(150, 150))
                .into(new SimpleTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(Bitmap bitmap, Transition<? super Bitmap> transition) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                        byte[] data = baos.toByteArray();
                        storageReference.child("filesmall/"+filename).putBytes(data);
                    }
                });
    }

    public static ChatModel.FileInfo getFileDetailFromUri(final Context context, final Uri uri) {
        ChatModel.FileInfo fileDetail = null;
        if (uri != null) {
            fileDetail = new ChatModel.FileInfo();
            // File Scheme.
            if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                File file = new File(uri.getPath());
                fileDetail.filename = file.getName();
                fileDetail.filesize = Util9.size2String(file.length());
            }
            // Content Scheme.
            else if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                Cursor returnCursor =
                        context.getContentResolver().query(uri, null, null, null, null);
                if (returnCursor != null && returnCursor.moveToFirst()) {
                    int nameIndex = returnCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = returnCursor.getColumnIndex(OpenableColumns.SIZE);
                    fileDetail.filename = returnCursor.getString(nameIndex);
                    fileDetail.filesize = Util9.size2String(returnCursor.getLong(sizeIndex));
                    returnCursor.close();
                }
            }
        }
        return fileDetail;
    }

    // =======================================================================================

    class RecyclerViewAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder>{
        final private RequestOptions requestOptions = new RequestOptions().transforms(new CenterCrop(), new RoundedCorners(90));

        List<ChatModel.Message> messageList;
        String beforeDay = null;
        MessageViewHolder beforeViewHolder;

        public RecyclerViewAdapter() {
            messageList = new ArrayList<>();

            databaseReference = db.child("rooms").child(roomID).child("messages");
            valueEventListener = databaseReference.addValueEventListener(new ValueEventListener(){
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    beforeDay = null;
                    messageList.clear();
                    db.child("rooms").child(roomID).child("unread").child(myUid).setValue(0);

                    Map<String, Object> readUsers = new HashMap<>();
                    for (DataSnapshot item: dataSnapshot.getChildren()) {
                        final ChatModel.Message message = item.getValue(ChatModel.Message.class);
                        if (!message.readUsers.containsKey(myUid)) {
                            message.readUsers.put(myUid, true);
                            readUsers.put(item.getKey(), message);
                        }
                        if (message.msgtype==null) message.msgtype="0"; // temp. this line will be deleted .
                        messageList.add(message);
                    }

                    if (readUsers.size()>0) {
                        db.child("rooms").child(roomID).child("messages").updateChildren(readUsers).addOnCompleteListener(new OnCompleteListener<Void>() {
                            @Override
                            public void onComplete(@NonNull Task<Void> task) {
                                notifyDataSetChanged();
                                recyclerView.scrollToPosition(messageList.size() - 1);
                            }
                        });
                    } else{
                        notifyDataSetChanged();
                        recyclerView.scrollToPosition(messageList.size() - 1);
                    }
                }

                @Override
                public void onCancelled(DatabaseError databaseError) {      }
            });
        }

        @Override
        public int getItemViewType(int position) {
            ChatModel.Message message = messageList.get(position);
            if (myUid.equals(message.uid) ) {
                switch(message.msgtype){
                    case "1": return R.layout.item_chatimage_right;
                    case "2": return R.layout.item_chatfile_right;
                    default:  return R.layout.item_chatmsg_right;
                }
            } else {
                switch(message.msgtype){
                    case "1": return R.layout.item_chatimage_left;
                    case "2": return R.layout.item_chatfile_left;
                    default:  return R.layout.item_chatmsg_left;
                }
            }
        }
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = null;
            view = LayoutInflater.from(parent.getContext()).inflate(viewType, parent, false);
            return new MessageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final MessageViewHolder messageViewHolder = (MessageViewHolder) holder;
            final ChatModel.Message message = messageList.get(position);

            setReadCounter(position, messageViewHolder.read_counter);

            String day = dateFormatDay.format( new Date( (long) message.timestamp) );
            String timestamp = dateFormatHour.format( new Date( (long) message.timestamp) );
            messageViewHolder.timestamp.setText(timestamp);
            if ("0".equals(message.msgtype)) {
                messageViewHolder.msg_item.setText(message.msg);
            } else
            if ("2".equals(message.msgtype)) {      // file
                db.child("rooms").child(roomID).child("files").child(message.msg).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        ChatModel.FileInfo fileInfo = dataSnapshot.getValue(ChatModel.FileInfo.class);
                        messageViewHolder.msg_item.setText(fileInfo.filename + "\n" + fileInfo.filesize);
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                    }
                });
            } else {                                // image
                Glide.with(getApplicationContext())
                        .load(storageReference.child("filesmall/"+message.msg))
                        .apply(new RequestOptions().override(1000, 1000))
                        .into(messageViewHolder.img_item);
            }

            if (! myUid.equals(message.uid)) {
                UserModel userModel = userList.get(message.uid);
                messageViewHolder.msg_name.setText(userModel.getUsernm());

                if (userModel.getUserphoto()==null) {
                    Glide.with(getApplicationContext()).load(R.drawable.user)
                            .apply(requestOptions)
                            .into(messageViewHolder.user_photo);
                } else{
                    Glide.with(getApplicationContext())
                            .load(storageReference.child("userPhoto/"+userModel.getUserphoto()))
                            .apply(requestOptions)
                            .into(messageViewHolder.user_photo);
                }
            }

            messageViewHolder.divider.setVisibility(View.INVISIBLE);
            messageViewHolder.divider.getLayoutParams().height = 0;

            if (position==0) {
                messageViewHolder.divider_date.setText(day);
                messageViewHolder.divider.setVisibility(View.VISIBLE);
                messageViewHolder.divider.getLayoutParams().height = 60;
            };
            if (!day.equals(beforeDay) && beforeDay!=null) {
                beforeViewHolder.divider_date.setText(beforeDay);
                beforeViewHolder.divider.setVisibility(View.VISIBLE);
                beforeViewHolder.divider.getLayoutParams().height = 60;
            }
            beforeViewHolder = messageViewHolder;
            beforeDay = day;
        }

        void setReadCounter (final int pos, final TextView textView) {
            int cnt = userList.size() - messageList.get(pos).readUsers.size();
            if (cnt > 0) {
                textView.setVisibility(View.VISIBLE);
                textView.setText(String.valueOf(cnt));
            } else {
                textView.setVisibility(View.INVISIBLE);
            }
        }

        @Override
        public int getItemCount() {
            return messageList.size();
        }

        private class MessageViewHolder extends RecyclerView.ViewHolder {
            public ImageView user_photo;
            public TextView msg_item;
            public ImageView img_item;
            public TextView msg_name;
            public TextView timestamp;
            public TextView read_counter;
            public LinearLayout divider;
            public TextView divider_date;

            public MessageViewHolder(View view) {
                super(view);
                user_photo = view.findViewById(R.id.user_photo);
                msg_item = view.findViewById(R.id.msg_item);
                img_item = view.findViewById(R.id.img_item);
                timestamp = view.findViewById(R.id.timestamp);
                msg_name = view.findViewById(R.id.msg_name);
                read_counter = view.findViewById(R.id.read_counter);
                divider = view.findViewById(R.id.divider);
                divider_date = view.findViewById(R.id.divider_date);
            }
        }
    }

    @Override
    public void onBackPressed() {
        //        super.onBackPressed();
        if (valueEventListener!=null) {
            databaseReference.removeEventListener(valueEventListener);
        }
        finish();;
    }
}
