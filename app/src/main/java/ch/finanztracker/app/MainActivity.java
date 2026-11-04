package ch.finanztracker.app;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.*;
import java.text.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    static final int GREEN=0xFF198754, RED=0xFFDC3545, BLUE=0xFF2878C7, DARK=0xFF18352A, BG=0xFFF4F7F5;
    DB db; LinearLayout root, list; Spinner accountSpin, monthSpin, yearSpin;
    TextView expenses, incomes, balance; ArrayList<String> accounts=new ArrayList<>(); int accountId=-1;
    String[] months={"Januar","Februar","März","April","Mai","Juni","Juli","August","September","Oktober","November","Dezember","Ganzes Jahr"};
    int selectedMonth=Calendar.getInstance().get(Calendar.MONTH)+1, selectedYear=Calendar.getInstance().get(Calendar.YEAR);
    final int REQ_CSV=11, REQ_PDF=12, REQ_BACKUP=13, REQ_RESTORE=14, REQ_CLOUD_FOLDER=15, REQ_RECEIPT_CAMERA=17, REQ_RECEIPT_PDF=18;
    ArrayList<String[]> exportRows=new ArrayList<>();
    String pendingCloudProvider="";
    static final int REQ_GOOGLE_AUTH=16;
    static final String DRIVE_SCOPE="https://www.googleapis.com/auth/drive.file";
    String googleAccessToken=null;
    long googleTokenValidUntil=0;
    boolean googleAuthorizationInProgress=false;
    boolean pendingGoogleRestore=false;
    boolean needsUnlockOnResume=false;
    final ExecutorService cloudExecutor=Executors.newSingleThreadExecutor();
    String activeReceiptPath=""; String pendingReceiptPdfPath=""; TextView receiptCameraButton;

    @Override public void onCreate(Bundle b){super.onCreate(b); getWindow().setStatusBarColor(0xFF126B43); getWindow().setNavigationBarColor(0xFFFFFFFF); getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR); db=new DB(this); ensureAccount(); buildUI(); checkPin(); trySilentGoogleAuthorization();}

    void trySilentGoogleAuthorization(){
        if("Google Drive".equals(getPreferences(0).getString("cloud_provider","")))authorizeGoogleDrive(false);
    }
    void authorizeGoogleDrive(boolean userInitiated){
        if(googleAuthorizationInProgress)return;
        googleAuthorizationInProgress=true;
        AuthorizationRequest request=AuthorizationRequest.builder().setRequestedScopes(Arrays.asList(new Scope(DRIVE_SCOPE))).build();
        Identity.getAuthorizationClient(this).authorize(request).addOnSuccessListener(result->{
            googleAuthorizationInProgress=false;
            if(result.hasResolution()){
                if(userInitiated){
                    try{startIntentSenderForResult(result.getPendingIntent().getIntentSender(),REQ_GOOGLE_AUTH,null,0,0,0);}
                    catch(android.content.IntentSender.SendIntentException e){toast("Google-Anmeldung konnte nicht gestartet werden.");}
                }
            }else{
                googleAccessToken=result.getAccessToken();
                googleTokenValidUntil=System.currentTimeMillis()+50L*60L*1000L;
                onGoogleDriveAuthorized();
            }
        }).addOnFailureListener(e->{googleAuthorizationInProgress=false;android.util.Log.e("MeineFinanzen","Google-Drive-Autorisierung fehlgeschlagen",e);if(userInitiated)toast("Google-Anmeldung fehlgeschlagen: "+e.getMessage());});
    }
    void handleGoogleAuthorizationResult(Intent data){
        googleAuthorizationInProgress=false;
        try{
            AuthorizationResult result=Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
            googleAccessToken=result.getAccessToken();
            googleTokenValidUntil=System.currentTimeMillis()+50L*60L*1000L;
            toast("Google Drive wurde verbunden.");
            onGoogleDriveAuthorized();
        }catch(Exception e){android.util.Log.e("MeineFinanzen","Google-Autorisierung fehlgeschlagen",e);String detail=e.toString();for(Throwable cause=e.getCause();cause!=null;cause=cause.getCause())detail+="\nUrsache: "+cause;showErrorDialog("Google-Autorisierung fehlgeschlagen",detail);}
    }
    void onGoogleDriveAuthorized(){
        getPreferences(0).edit().putString("cloud_provider","Google Drive").remove("cloud_tree_uri").apply();
        if(pendingGoogleRestore){pendingGoogleRestore=false;downloadGoogleDriveBackup();}
        else uploadGoogleDriveBackup();
    }
    void restoreGoogleDriveBackup(){
        pendingGoogleRestore=true;
        if(googleAccessToken!=null&&System.currentTimeMillis()<googleTokenValidUntil){pendingGoogleRestore=false;downloadGoogleDriveBackup();}
        else authorizeGoogleDrive(true);
    }
    String findOrCreateGoogleDriveFolder(okhttp3.OkHttpClient client,String token)throws Exception{
        String query=java.net.URLEncoder.encode("name = 'MeineFinanzen' and mimeType = 'application/vnd.google-apps.folder' and trashed = false","UTF-8");
        okhttp3.Request find=new okhttp3.Request.Builder().url("https://www.googleapis.com/drive/v3/files?q="+query+"&spaces=drive&pageSize=10&fields=files(id)").header("Authorization","Bearer "+token).get().build();
        try(okhttp3.Response response=client.newCall(find).execute()){
            String body=response.body()==null?"":response.body().string();
            if(!response.isSuccessful())throw new IOException("Google Drive Ordnersuche HTTP "+response.code()+": "+body);
            org.json.JSONArray folders=new org.json.JSONObject(body).optJSONArray("files");
            if(folders!=null&&folders.length()>0)return folders.getJSONObject(0).getString("id");
        }
        org.json.JSONObject metadata=new org.json.JSONObject();metadata.put("name","MeineFinanzen");metadata.put("mimeType","application/vnd.google-apps.folder");
        okhttp3.Request create=new okhttp3.Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id").header("Authorization","Bearer "+token).post(okhttp3.RequestBody.create(metadata.toString(),okhttp3.MediaType.parse("application/json; charset=utf-8"))).build();
        try(okhttp3.Response response=client.newCall(create).execute()){
            String body=response.body()==null?"":response.body().string();
            if(!response.isSuccessful())throw new IOException("Google Drive Ordner konnte nicht erstellt werden HTTP "+response.code()+": "+body);
            String id=new org.json.JSONObject(body).optString("id","");
            if(id.isEmpty())throw new IOException("Google Drive hat keine Ordner-ID zurückgegeben.");
            return id;
        }
    }
    void downloadGoogleDriveBackup(){
        if(googleAccessToken==null||googleAccessToken.isEmpty()){toast("Bitte zuerst Google Drive verbinden.");return;}
        final String token=googleAccessToken;
        cloudExecutor.execute(()->{
            try{
                okhttp3.OkHttpClient client=new okhttp3.OkHttpClient();
                String folderId=findOrCreateGoogleDriveFolder(client,token);
                String query=java.net.URLEncoder.encode("name = 'Meine-Finanzen-Backup.json' and '"+folderId+"' in parents and trashed = false","UTF-8");
                okhttp3.Request listRequest=new okhttp3.Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files?q="+query+"&spaces=drive&orderBy=modifiedTime%20desc&pageSize=10&fields=files(id,name,modifiedTime)")
                    .header("Authorization","Bearer "+token).get().build();
                String fileId=null;
                try(okhttp3.Response response=client.newCall(listRequest).execute()){
                    String body=response.body()==null?"":response.body().string();
                    if(!response.isSuccessful())throw new IOException("Google Drive Suche HTTP "+response.code()+": "+body);
                    org.json.JSONArray files=new org.json.JSONObject(body).optJSONArray("files");
                    if(files!=null&&files.length()>0)fileId=files.getJSONObject(0).optString("id",null);
                }
                if(fileId==null||fileId.isEmpty())throw new IOException("Im Google-Drive-Ordner MeineFinanzen wurde kein Backup namens Meine-Finanzen-Backup.json gefunden.");
                okhttp3.Request getRequest=new okhttp3.Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files/"+fileId+"?alt=media")
                    .header("Authorization","Bearer "+token).get().build();
                final String backup;
                try(okhttp3.Response response=client.newCall(getRequest).execute()){
                    backup=response.body()==null?"":response.body().string();
                    if(!response.isSuccessful())throw new IOException("Google Drive Download HTTP "+response.code()+": "+backup);
                }
                runOnUiThread(()->confirmCloudRestore(backup));
            }catch(Exception e){android.util.Log.e("MeineFinanzen","Google-Drive-Wiederherstellung fehlgeschlagen",e);String detail=e.toString();for(Throwable cause=e.getCause();cause!=null;cause=cause.getCause())detail+="\nUrsache: "+cause;final String error=detail;runOnUiThread(()->showErrorDialog("Google-Drive-Import fehlgeschlagen",error));}
        });
    }
    void confirmCloudRestore(String json){
        try{
            org.json.JSONObject backup=new org.json.JSONObject(json);
            backup.getJSONArray("accounts");backup.getJSONArray("entries");
            new AlertDialog.Builder(this).setTitle("Cloud-Backup wiederherstellen")
                .setMessage("Das Backup wurde in Google Drive gefunden. Wenn du fortfährst, werden die aktuell gespeicherten Konten und Einträge durch den Stand aus der Cloud ersetzt.")
                .setNegativeButton("Abbrechen",null)
                .setPositiveButton("Wiederherstellen",(d,w)->{try{restoreBackupJson(json);}catch(Exception e){showErrorDialog("Backup konnte nicht wiederhergestellt werden",e.toString());}}).show();
        }catch(Exception e){showErrorDialog("Ungültiges Cloud-Backup","Die gefundene Datei ist kein gültiges Meine-Finanzen-Backup.\n"+e.getMessage());}
    }
    void uploadGoogleDriveBackup(){
        if(googleAccessToken==null||googleAccessToken.isEmpty())return;
        final String token=googleAccessToken;
        final String json;
        try{json=backupJson();}catch(Exception e){android.util.Log.e("MeineFinanzen","Backup konnte nicht erstellt werden",e);return;}
        cloudExecutor.execute(()->{
            try{
                okhttp3.OkHttpClient client=new okhttp3.OkHttpClient();
                String folderId=findOrCreateGoogleDriveFolder(client,token);
                String fileQuery=java.net.URLEncoder.encode("name = 'Meine-Finanzen-Backup.json' and '"+folderId+"' in parents and trashed = false","UTF-8");
                okhttp3.Request findFile=new okhttp3.Request.Builder().url("https://www.googleapis.com/drive/v3/files?q="+fileQuery+"&spaces=drive&pageSize=10&fields=files(id)").header("Authorization","Bearer "+token).get().build();
                String fileId=null;
                try(okhttp3.Response response=client.newCall(findFile).execute()){
                    String responseText=response.body()==null?"":response.body().string();
                    if(!response.isSuccessful())throw new IOException("Google Drive Suche HTTP "+response.code()+": "+responseText);
                    org.json.JSONArray files=new org.json.JSONObject(responseText).optJSONArray("files");
                    if(files!=null&&files.length()>0)fileId=files.getJSONObject(0).optString("id",null);
                }
                okhttp3.MediaType media=okhttp3.MediaType.parse("application/json; charset=utf-8");
                okhttp3.RequestBody body=okhttp3.RequestBody.create(json,media);
                okhttp3.Request request;
                if(fileId==null||fileId.isEmpty()){
                    org.json.JSONObject metadataJson=new org.json.JSONObject();metadataJson.put("name","Meine-Finanzen-Backup.json");metadataJson.put("parents",new org.json.JSONArray().put(folderId));
                    okhttp3.RequestBody metadata=okhttp3.RequestBody.create(metadataJson.toString(),media);
                    okhttp3.RequestBody multipart=new okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MediaType.parse("multipart/related"))
                        .addPart(metadata)
                        .addPart(body).build();
                    request=new okhttp3.Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id")
                        .header("Authorization","Bearer "+token).post(multipart).build();
                }else{
                    request=new okhttp3.Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files/"+fileId+"?uploadType=media")
                        .header("Authorization","Bearer "+token).patch(body).build();
                }
                try(okhttp3.Response response=client.newCall(request).execute()){
                    String responseText=response.body()==null?"":response.body().string();
                    if(!response.isSuccessful())throw new IOException("Google Drive HTTP "+response.code()+": "+responseText);
                    String savedId=fileId;
                    if(savedId==null||savedId.isEmpty()){
                        String newId=new org.json.JSONObject(responseText).optString("id","");
                        if(newId.isEmpty())throw new IOException("Keine Datei-ID erhalten.");
                        getPreferences(0).edit().putString("google_drive_file_id",newId).apply();
                    }else getPreferences(0).edit().putString("google_drive_file_id",savedId).apply();
                    runOnUiThread(()->toast("Backup erfolgreich in Google Drive gespeichert."));
                }
            }catch(Exception e){android.util.Log.e("MeineFinanzen","Google-Drive-Backup fehlgeschlagen",e);String detail=e.toString();for(Throwable cause=e.getCause();cause!=null;cause=cause.getCause())detail+="\nUrsache: "+cause;final String errorDetail=detail;runOnUiThread(()->showErrorDialog("Google-Drive-Backup fehlgeschlagen",errorDetail));}
        });
    }
    @Override protected void onStop(){super.onStop();needsUnlockOnResume=true;saveAutomaticBackup();}
    @Override protected void onResume(){super.onResume();updatePeriodToCurrent();if(needsUnlockOnResume){needsUnlockOnResume=false;checkPin();}}
    void updatePeriodToCurrent(){
        Calendar now=Calendar.getInstance();int month=now.get(Calendar.MONTH)+1,year=now.get(Calendar.YEAR);
        boolean changed=selectedMonth!=month||selectedYear!=year;
        if(!changed)return;
        selectedMonth=month;selectedYear=year;
        if(monthSpin!=null)monthSpin.setSelection(month-1);
        if(yearSpin!=null&&yearSpin.getAdapter()!=null){
            int position=-1;for(int i=0;i<yearSpin.getAdapter().getCount();i++)if(String.valueOf(year).equals(yearSpin.getAdapter().getItem(i))){position=i;break;}
            if(position>=0)yearSpin.setSelection(position);
        }
        refresh();
    }
    int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,1);return t;}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    void pad(View v,int a){v.setPadding(dp(a),dp(a),dp(a),dp(a));}
    Button button(String label,int color){Button b=new Button(this);b.setText(label);b.setTextColor(0xFFFFFFFF);b.setTextSize(15);b.setAllCaps(false);b.setBackground(shape(color,12));return b;}
    void ensureAccount(){if(db.countAccounts()==0)db.addAccount("Hauptkonto");}
    void buildUI(){
        root=column();root.setBackgroundColor(BG);setContentView(root);
        LinearLayout header=row();header.setBackgroundColor(GREEN);header.setPadding(dp(12),dp(8),dp(16),dp(8));root.addView(header,lp(-1,60));
        Button menu=button("☰",0xFF126B43);header.addView(menu,lp(48,44));menu.setOnClickListener(v->menu());
        TextView title=text("Meine Finanzen",21,0xFFFFFFFF,true);LinearLayout.LayoutParams tp=lp(0,-2);tp.weight=1;tp.leftMargin=dp(12);header.addView(title,tp);
        TextView coin=text("🪙",29,0xFFFFFFFF,true);header.addView(coin);
        accountSpin=new Spinner(this);LinearLayout accountBox=column();accountBox.setPadding(dp(16),dp(8),dp(16),dp(4));accountBox.addView(text("Konto auswählen",12,0xFF52645A,true));accountBox.addView(accountSpin,lp(-1,48));root.addView(accountBox);
        LinearLayout actions=row();actions.setPadding(dp(16),dp(6),dp(16),dp(8));root.addView(actions,lp(-1,86));
        Button plus=button("+",GREEN),minus=button("−",RED);plus.setTextSize(30);minus.setTextSize(30);plus.setContentDescription("Einnahme hinzufügen");minus.setContentDescription("Ausgabe hinzufügen");
        LinearLayout.LayoutParams ap=lp(0,68);ap.weight=1;ap.rightMargin=dp(6);actions.addView(plus,ap);LinearLayout.LayoutParams mp=lp(0,68);mp.weight=1;mp.leftMargin=dp(6);actions.addView(minus,mp);
        plus.setOnClickListener(v->entryDialog(true,null));minus.setOnClickListener(v->entryDialog(false,null));
        LinearLayout filters=row();filters.setPadding(dp(16),dp(4),dp(16),dp(8));root.addView(filters,lp(-1,62));
        monthSpin=new Spinner(this);yearSpin=new Spinner(this);LinearLayout.LayoutParams fp=lp(0,48);fp.weight=1;fp.rightMargin=dp(5);filters.addView(monthSpin,fp);LinearLayout.LayoutParams yp=lp(0,48);yp.weight=1;yp.leftMargin=dp(5);filters.addView(yearSpin,yp);
        setupFilters();
        ScrollView sc=new ScrollView(this);sc.setFillViewport(false);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        list=column();list.setPadding(dp(16),dp(2),dp(16),dp(12));sc.addView(list);
        Button export=button("⇩   Exportieren (PDF / Excel)",0xFF356B9A);LinearLayout exportWrap=column();exportWrap.setPadding(dp(16),dp(4),dp(16),dp(8));exportWrap.addView(export,lp(-1,48));root.addView(exportWrap);export.setOnClickListener(v->exportDialog());
        LinearLayout footer=row();footer.setBackgroundColor(0xFFFFFFFF);footer.setElevation(dp(8));footer.setPadding(dp(10),dp(10),dp(10),dp(10));root.addView(footer,lp(-1,72));
        expenses=text("Ausgaben\nCHF 0.00",13,RED,true);incomes=text("Einnahmen\nCHF 0.00",13,GREEN,true);balance=text("Saldo\nCHF 0.00",13,BLUE,true);
        for(TextView t:new TextView[]{expenses,incomes,balance}){t.setGravity(Gravity.CENTER);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);footer.addView(t,p);}
        reloadAccounts();refresh();
    }
    void setupFilters(){
        ArrayList<String> ms=new ArrayList<>(Arrays.asList(months));ArrayAdapter<String> ma=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,ms);monthSpin.setAdapter(ma);monthSpin.setSelection(Math.max(0,Math.min(12,selectedMonth-1)));
        ArrayList<String> ys=new ArrayList<>();int y=Calendar.getInstance().get(Calendar.YEAR);for(int i=y-8;i<=2040;i++)ys.add(""+i);
        yearSpin.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,ys));yearSpin.setSelection(ys.indexOf(""+selectedYear));
        monthSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){selectedMonth=pos==12?0:pos+1;refresh();}});
        yearSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){selectedYear=Integer.parseInt(ys.get(pos));refresh();}});
    }
    void reloadAccounts(){accounts=db.accounts();ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,accounts);accountSpin.setAdapter(a);accountSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){accountId=db.accountId(accounts.get(pos));refresh();}});if(!accounts.isEmpty()&&accountId>0){int i=accounts.indexOf(db.accountName(accountId));if(i>=0)accountSpin.setSelection(i);}}
    void refresh(){
        if(list==null)return;list.removeAllViews();if(accountId<0&& !accounts.isEmpty())accountId=db.accountId(accounts.get(0));
        double inc=0,exp=0;Cursor c=db.entries(accountId,selectedMonth,selectedYear);
        if(c.getCount()==0){TextView empty=text("Noch keine Einträge in diesem Zeitraum.",14,0xFF77847D,false);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(35),0,dp(35));list.addView(empty);}
        while(c.moveToNext()){
            int id=c.getInt(0);String purpose=c.getString(1),date=c.getString(2);double amount=c.getDouble(3);boolean income=c.getInt(4)==1;
            if(income)inc+=amount;else exp+=amount;
            LinearLayout card=row();card.setBackground(shape(0xFFFFFFFF,12));card.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams cp=lp(-1,-2);cp.bottomMargin=dp(8);list.addView(card,cp);
            View stripe=new View(this);stripe.setBackgroundColor(income?GREEN:RED);card.addView(stripe,lp(4,44));
            LinearLayout info=column();LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-2,1);ip.leftMargin=dp(10);card.addView(info,ip);
            info.addView(text(purpose.isEmpty()?"Ohne Zweck":purpose,15,DARK,true));info.addView(text(date,12,0xFF718078,false));
            if(c.getColumnCount()>5){String receiptPath=c.getString(5);if(receiptPath!=null&&!receiptPath.isEmpty()&&new File(receiptPath).exists()){TextView receipt=text("🧾",17,DARK,true);LinearLayout.LayoutParams rp=lp(28,32);rp.rightMargin=dp(5);card.addView(receipt,rp);receipt.setOnClickListener(v->showReceipt(receiptPath));}}
            TextView val=text((income?"+ ":"− ")+money(amount),15,income?GREEN:RED,true);card.addView(val);
            card.setOnClickListener(v->confirmEdit(id,purpose,date,amount,income));
        }
        c.close();expenses.setText("Ausgaben\n"+money(exp));incomes.setText("Einnahmen\n"+money(inc));balance.setText("Saldo\n"+money(inc-exp));
    }
    String money(double n){return String.format(Locale.GERMANY,"CHF %,.2f",n);}
    void entryDialog(boolean income,int[] existing){
        final EditText purpose=new EditText(this),amount=new EditText(this);purpose.setHint("Zweck");amount.setHint("Betrag in CHF");amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),0);
        LinearLayout purposeRow=row();purposeRow.addView(purpose,new LinearLayout.LayoutParams(0,dp(52),1));
        receiptCameraButton=text("📷",23,GREEN,true);receiptCameraButton.setGravity(Gravity.CENTER);purposeRow.addView(receiptCameraButton,lp(48,48));
        receiptCameraButton.setOnClickListener(v->captureReceipt());box.addView(purposeRow);box.addView(amount);activeReceiptPath="";
        final Calendar cal=Calendar.getInstance();final TextView date=text("Datum: "+new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).format(cal.getTime())+"  ✎",15,GREEN,true);date.setPadding(0,dp(14),0,dp(8));box.addView(date);
        date.setOnClickListener(v->new DatePickerDialog(this,(view,yy,mm,dd)->{cal.set(yy,mm,dd);date.setText("Datum: "+String.format(Locale.GERMANY,"%02d.%02d.%04d",dd,mm+1,yy)+"  ✎");},cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show());
        if(existing!=null){Cursor e=db.one(existing[0]);if(e.moveToFirst()){purpose.setText(e.getString(1));amount.setText(""+e.getDouble(3));activeReceiptPath=e.getString(5)==null?"":e.getString(5);if(!activeReceiptPath.isEmpty()&&new File(activeReceiptPath).exists())receiptCameraButton.setText("🧾");try{cal.setTime(new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).parse(e.getString(2)));}catch(Exception ignored){}date.setText("Datum: "+e.getString(2)+"  ✎");}e.close();}
        new AlertDialog.Builder(this).setTitle(income?"Einnahme erfassen":"Ausgabe erfassen").setView(box).setNegativeButton("Abbrechen",null).setPositiveButton(existing==null?"Speichern":"Änderungen speichern",(d,w)->{
            String p=purpose.getText().toString().trim();String a=amount.getText().toString().trim().replace(",",".");if(a.isEmpty()){toast("Bitte einen Betrag eingeben.");return;}
            try{double n=Double.parseDouble(a);if(n<=0)throw new Exception();String dt=new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).format(cal.getTime());if(existing==null)db.addEntry(accountId,p,dt,n,income,activeReceiptPath);else db.updateEntry(existing[0],p,dt,n,income,activeReceiptPath);refresh();}catch(Exception ex){toast("Bitte einen gültigen Betrag eingeben.");}
        }).show();
    }
    void captureReceipt(){
        try{File dir=new File(getFilesDir(),"receipts");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Quittungsordner konnte nicht erstellt werden.");File photo=new File(dir,"receipt_"+System.currentTimeMillis()+".jpg");activeReceiptPath=photo.getAbsolutePath();Uri uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".fileprovider",photo);Intent camera=new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);camera.putExtra(android.provider.MediaStore.EXTRA_OUTPUT,uri);camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);camera.setClipData(ClipData.newUri(getContentResolver(),"Quittung",uri));if(camera.resolveActivity(getPackageManager())!=null)startActivityForResult(camera,REQ_RECEIPT_CAMERA);else{activeReceiptPath="";toast("Keine Kamera-App gefunden.");}}catch(Exception e){activeReceiptPath="";showErrorDialog("Kamera konnte nicht geöffnet werden",e.toString());}
    }
    void compressReceipt(File file)throws Exception{BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);int sample=1;while(bounds.outWidth/sample>2000||bounds.outHeight/sample>2000)sample*=2;BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(bitmap==null)throw new IOException("Foto konnte nicht gelesen werden.");int max=1800;float scale=Math.min(1f,Math.min((float)max/bitmap.getWidth(),(float)max/bitmap.getHeight()));if(scale<1f){Bitmap scaled=Bitmap.createScaledBitmap(bitmap,Math.round(bitmap.getWidth()*scale),Math.round(bitmap.getHeight()*scale),true);bitmap.recycle();bitmap=scaled;}try(FileOutputStream out=new FileOutputStream(file,false)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,78,out))throw new IOException("Foto konnte nicht komprimiert werden.");out.flush();}bitmap.recycle();}
    void showReceipt(String path){try{Bitmap bitmap=BitmapFactory.decodeFile(path);if(bitmap==null)throw new IOException("Quittungsbild kann nicht geöffnet werden.");ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);ScrollView scroll=new ScrollView(this);scroll.addView(image);new AlertDialog.Builder(this).setTitle("Quittung").setView(scroll).setNegativeButton("Schliessen",null).setPositiveButton("Als PDF speichern",(d,w)->{pendingReceiptPdfPath=path;Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.addCategory(Intent.CATEGORY_OPENABLE);save.setType("application/pdf");save.putExtra(Intent.EXTRA_TITLE,"Quittung.pdf");startActivityForResult(save,REQ_RECEIPT_PDF);}).show();}catch(Exception e){showErrorDialog("Quittung konnte nicht geöffnet werden",e.toString());}}
    void writeReceiptPdf(Uri uri)throws Exception{Bitmap bitmap=BitmapFactory.decodeFile(pendingReceiptPdfPath);if(bitmap==null)throw new IOException("Quittungsbild ist nicht verfügbar.");PdfDocument pdf=new PdfDocument();PdfDocument.PageInfo info=new PdfDocument.PageInfo.Builder(595,842,1).create();PdfDocument.Page page=pdf.startPage(info);Canvas canvas=page.getCanvas();Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);float scale=Math.min(535f/bitmap.getWidth(),782f/bitmap.getHeight());float width=bitmap.getWidth()*scale,height=bitmap.getHeight()*scale;float left=(595-width)/2f,top=(842-height)/2f;canvas.drawBitmap(bitmap,null,new RectF(left,top,left+width,top+height),paint);pdf.finishPage(page);try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("PDF-Datei kann nicht geschrieben werden.");pdf.writeTo(out);}pdf.close();bitmap.recycle();pendingReceiptPdfPath="";toast("Quittung als PDF gespeichert.");}
    byte[] readFileBytes(String path)throws Exception{try(InputStream in=new FileInputStream(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
    String restoreReceiptImage(String encoded)throws Exception{byte[] bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);File dir=new File(getFilesDir(),"receipts");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Quittungsordner konnte nicht erstellt werden.");File file=new File(dir,"restored_"+System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8)+".jpg");try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.flush();}return file.getAbsolutePath();}
    void confirmEdit(int id,String p,String date,double amt,boolean income){
        new AlertDialog.Builder(this).setMessage("Willst du den Eintrag bearbeiten?").setNegativeButton("Nein",null).setPositiveButton("Ja",(d,w)->new AlertDialog.Builder(this).setTitle("Eintrag bearbeiten").setItems(new String[]{"Bearbeiten","Löschen","Abbrechen"},(x,which)->{if(which==0)entryDialog(income,new int[]{id});else if(which==1)new AlertDialog.Builder(this).setMessage("Eintrag wirklich löschen?").setNegativeButton("Nein",null).setPositiveButton("Löschen",(a,b)->{db.deleteEntry(id);refresh();}).show();}).show()).show();
    }
    void exportDialog(){new AlertDialog.Builder(this).setTitle("Exportformat wählen").setItems(new String[]{"PDF (A4)","Excel (CSV)"},(d,w)->{exportRows.clear();Cursor c=db.entries(accountId,selectedMonth,selectedYear);while(c.moveToNext())exportRows.add(new String[]{(c.getString(1)==null||c.getString(1).trim().isEmpty())?"Ohne Zweck":c.getString(1),c.getString(2),String.format(Locale.US,"%.2f",c.getDouble(3)),c.getInt(4)==1?"Einnahme":"Ausgabe"});c.close();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType(w==0?"application/pdf":"text/csv");i.putExtra(Intent.EXTRA_TITLE,w==0?"Meine-Finanzen.pdf":"Meine-Finanzen.csv");startActivityForResult(i,w==0?REQ_PDF:REQ_CSV);}).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==REQ_GOOGLE_AUTH){android.util.Log.d("MeineFinanzen","Google Auth resultCode="+result+" data="+(data!=null));if(data!=null)handleGoogleAuthorizationResult(data);else showErrorDialog("Google-Anmeldung abgebrochen","Google hat kein Ergebnis an die App zurückgegeben. Rückgabecode: "+result);return;}
        if(request==REQ_RECEIPT_CAMERA){if(result==RESULT_OK&&!activeReceiptPath.isEmpty()){File f=new File(activeReceiptPath);if(f.exists()&&f.length()>0){try{compressReceipt(f);if(receiptCameraButton!=null)receiptCameraButton.setText("🧾");toast("Quittung aufgenommen.");}catch(Exception ex){showErrorDialog("Quittung konnte nicht verarbeitet werden",ex.toString());}}else toast("Kein Quittungsfoto erhalten.");}else toast("Fotoaufnahme abgebrochen.");return;}
        if(result!=RESULT_OK||data==null)return;
        try{Uri u=data.getData();if(request==REQ_CSV)writeCsv(u);else if(request==REQ_PDF)writePdf(u);else if(request==REQ_BACKUP)writeBackup(u);else if(request==REQ_RESTORE)readBackup(u);else if(request==REQ_CLOUD_FOLDER)saveCloudFolder(data);else if(request==REQ_RECEIPT_PDF)writeReceiptPdf(u);}
        catch(Exception e){toast("Datei konnte nicht verarbeitet werden: "+e.getMessage());}
    }
    void writeCsv(Uri uri)throws Exception{try(Writer w=new OutputStreamWriter(getContentResolver().openOutputStream(uri),"UTF-8")){w.write("\uFEFFZweck;Datum;Betrag CHF;Art\n");for(String[]r:exportRows)w.write(csv(r[0])+";"+r[1]+";"+r[2].replace(".",",")+";"+r[3]+"\n");}toast("Excel-Datei gespeichert.");}
    String csv(String s){String q=String.valueOf((char)34);return q+s.replace(q,q+q)+q;}
    void writePdf(Uri uri)throws Exception{
        PdfDocument pdf=new PdfDocument();PdfDocument.PageInfo pi=new PdfDocument.PageInfo.Builder(595,842,1).create();PdfDocument.Page page=pdf.startPage(pi);Canvas c=page.getCanvas();Paint p=new Paint(3);p.setColor(GREEN);c.drawRect(0,0,595,90,p);p.setColor(Color.WHITE);p.setTextSize(25);p.setFakeBoldText(true);c.drawText("Meine Finanzen",38,52,p);p.setTextSize(12);p.setFakeBoldText(false);c.drawText("Konto: "+db.accountName(accountId)+"   |   "+(selectedMonth==0?"Ganzes Jahr":months[selectedMonth-1])+" "+selectedYear,38,74,p);
        int y=130;p.setColor(DARK);p.setTextSize(12);p.setFakeBoldText(true);c.drawText("Zweck",38,y,p);c.drawText("Datum",330,y,p);c.drawText("Betrag CHF",440,y,p);y+=22;p.setFakeBoldText(false);
        int pageNumber=1;
        for(String[]r:exportRows){
            if(y>770){pdf.finishPage(page);pageNumber++;pi=new PdfDocument.PageInfo.Builder(595,842,pageNumber).create();page=pdf.startPage(pi);c=page.getCanvas();y=55;}
            p.setColor(DARK);p.setTextSize(12);c.drawText(r[0].length()>42?r[0].substring(0,39)+"...":r[0],38,y,p);c.drawText(r[1],330,y,p);
            int amountColor=r[3].equals("Einnahme")?GREEN:RED;
            p.setColor(r[3].equals("Einnahme")?0xFFE3F3E8:0xFFFBE5E7);
            c.drawRoundRect(432,y-16,558,y+5,7,7,p);
            p.setColor(amountColor);p.setFakeBoldText(true);c.drawText(r[2],440,y,p);p.setFakeBoldText(false);y+=24;
        }
        double currentBalance=db.accountBalance(accountId);
        p.setColor(0xFFEAF1FA);c.drawRoundRect(330,790,558,824,8,8,p);
        p.setColor(BLUE);p.setTextSize(13);p.setFakeBoldText(true);String saldoText="Aktueller Saldo: "+money(currentBalance);float saldoWidth=p.measureText(saldoText);c.drawText(saldoText,550-saldoWidth,812,p);p.setFakeBoldText(false);
        pdf.finishPage(page);try(OutputStream o=getContentResolver().openOutputStream(uri)){pdf.writeTo(o);}pdf.close();toast("PDF im A4-Format gespeichert.");
    }
    void menu(){String[]opts={"Konten verwalten","Sicherheit","Datensicherung","Abbrechen"};new AlertDialog.Builder(this).setTitle("Menü").setItems(opts,(d,w)->{if(w==0)accountsDialog();if(w==1)securityDialog();if(w==2)backupDialog();}).show();}
    void accountsDialog(){String[]opts={"Konto hinzufügen","Konto umbenennen","Konto löschen"};new AlertDialog.Builder(this).setTitle("Konten verwalten").setItems(opts,(d,w)->{if(w==0)askText("Neues Konto",name->{db.addAccount(name);accountId=-1;reloadAccounts();refresh();});else if(w==1)askText("Neuer Kontoname",name->{db.renameAccount(accountId,name);reloadAccounts();refresh();});else new AlertDialog.Builder(this).setMessage("Konto und alle zugehörigen Einträge löschen?").setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(a,b)->{db.deleteAccount(accountId);ensureAccount();accountId=-1;reloadAccounts();refresh();}).show();}).show();}
    interface TextDone{void done(String s);}
    void askText(String title,TextDone done){EditText e=new EditText(this);e.setHint("Name");new AlertDialog.Builder(this).setTitle(title).setView(e).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",(d,w)->{String s=e.getText().toString().trim();if(!s.isEmpty())done.done(s);}).show();}
    void securityDialog(){
        boolean pinOn=getPreferences(0).getBoolean("pin_on",false);
        boolean bioOn=getPreferences(0).getBoolean("bio_on",false);
        String[]opts={"PIN-Abfrage "+(pinOn?"deaktivieren":"aktivieren"),"PIN ändern","Fingerabdruck "+(bioOn?"deaktivieren":"aktivieren")};
        new AlertDialog.Builder(this).setTitle("Sicherheit").setItems(opts,(d,w)->{
            if(w==0){if(pinOn){getPreferences(0).edit().putBoolean("pin_on",false).apply();toast("PIN-Abfrage deaktiviert.");}else setNewPin();}
            else if(w==1)setNewPin();
            else toggleBiometric();
        }).show();
    }
    void setNewPin(){
        EditText e=new EditText(this);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);e.setHint("Neue PIN (mind. 4 Ziffern)");
        new AlertDialog.Builder(this).setTitle("PIN festlegen").setView(e).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",(d,w)->{
            String p=e.getText().toString();if(p.length()<4){toast("PIN muss mindestens 4 Ziffern haben.");return;}
            getPreferences(0).edit().putString("pin",p).putBoolean("pin_on",true).apply();toast("PIN aktiviert.");
        }).show();
    }
    void toggleBiometric(){
        boolean enabled=getPreferences(0).getBoolean("bio_on",false);
        if(enabled){getPreferences(0).edit().putBoolean("bio_on",false).apply();toast("Fingerabdruck-Anmeldung deaktiviert.");return;}
        BiometricManager manager=BiometricManager.from(this);
        if(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){toast("Auf diesem Gerät ist kein verwendbarer Fingerabdruck eingerichtet.");return;}
        String savedPin=getPreferences(0).getString("pin","");
        if(savedPin.isEmpty()){setBiometricPin();return;}
        EditText e=new EditText(this);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);e.setHint("Vorhandene PIN bestätigen");
        new AlertDialog.Builder(this).setTitle("Fingerabdruck aktivieren").setMessage("Bitte bestätige deine PIN. Die PIN-Abfrage beim App-Start bleibt davon unabhängig.").setView(e)
            .setNegativeButton("Abbrechen",null).setPositiveButton("Bestätigen",(d,w)->{
                if(!e.getText().toString().equals(getPreferences(0).getString("pin",""))){toast("PIN ist nicht korrekt.");return;}
                getPreferences(0).edit().putBoolean("bio_on",true).apply();toast("Fingerabdruck-Anmeldung aktiviert.");
            }).show();
    }
    void setBiometricPin(){
        EditText e=new EditText(this);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);e.setHint("PIN (mind. 4 Ziffern)");
        new AlertDialog.Builder(this).setTitle("PIN für Fingerabdruck festlegen").setMessage("Diese PIN dient als Ersatz, falls der Fingerabdruck nicht funktioniert. Die PIN-Abfrage beim App-Start wird nicht aktiviert.").setView(e)
            .setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",(d,w)->{
                String p=e.getText().toString();if(p.length()<4){toast("PIN muss mindestens 4 Ziffern haben.");return;}
                getPreferences(0).edit().putString("pin",p).putBoolean("bio_on",true).apply();toast("Fingerabdruck-Anmeldung aktiviert.");
            }).show();
    }
    void checkPin(){
        boolean pinOn=getPreferences(0).getBoolean("pin_on",false);
        boolean bioOn=getPreferences(0).getBoolean("bio_on",false);
        if(bioOn){showBiometricPrompt();return;}
        if(pinOn)showPinDialog();
    }
    void showBiometricPrompt(){
        BiometricManager manager=BiometricManager.from(this);
        if(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){showPinDialog();return;}
        BiometricPrompt prompt=new BiometricPrompt(this,ContextCompat.getMainExecutor(this),new BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){super.onAuthenticationSucceeded(result);}
            @Override public void onAuthenticationError(int errorCode,CharSequence errString){super.onAuthenticationError(errorCode,errString);showPinDialog();}
        });
        BiometricPrompt.PromptInfo info=new BiometricPrompt.PromptInfo.Builder().setTitle("Meine Finanzen entsperren").setSubtitle("Mit Fingerabdruck anmelden").setNegativeButtonText("Mit PIN fortfahren").build();
        prompt.authenticate(info);
    }
    void showPinDialog(){
        EditText e=new EditText(this);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);e.setHint("PIN");
        new AlertDialog.Builder(this).setTitle("PIN eingeben").setMessage("Bitte PIN zum Öffnen eingeben.").setView(e).setCancelable(false)
            .setPositiveButton("Entsperren",(d,w)->{if(!e.getText().toString().equals(getPreferences(0).getString("pin",""))){toast("PIN falsch. Bitte App erneut öffnen und nochmals versuchen.");finish();}})
            .setNegativeButton("Beenden",(d,w)->finish()).show();
    }
    void backupDialog(){
        boolean cloud=getPreferences(0).contains("cloud_tree_uri")||"Google Drive".equals(getPreferences(0).getString("cloud_provider",""));
        String provider=getPreferences(0).getString("cloud_provider","");
        String cloudLabel=provider.isEmpty()?"Cloud-Backup":("Cloud-Backup ("+provider+")");
        String[] opts={"Backup auf Gerät speichern","Backup importieren / wiederherstellen","Backup aus Google Drive importieren",cloud?cloudLabel+" ändern":"Cloud-Backup einrichten","Cloud-Backup deaktivieren"};
        new AlertDialog.Builder(this).setTitle("Datensicherung").setItems(opts,(d,w)->{
            if(w==0){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"Meine-Finanzen-Backup.json");startActivityForResult(i,REQ_BACKUP);}
            else if(w==1){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");startActivityForResult(i,REQ_RESTORE);}
            else if(w==2)restoreGoogleDriveBackup();
            else if(w==3)chooseCloudProvider();
            else if(w==4){getPreferences(0).edit().remove("cloud_tree_uri").remove("cloud_provider").remove("google_drive_file_id").apply();googleAccessToken=null;googleTokenValidUntil=0;toast("Cloud-Backup deaktiviert. Die lokale automatische Sicherung bleibt aktiv.");}
        }).show();
    }
    void chooseCloudProvider(){
        authorizeGoogleDrive(true);
    }
    void saveCloudFolder(Intent data){
        Uri tree=data.getData();if(tree==null)return;
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try{getContentResolver().takePersistableUriPermission(tree,flags);}catch(Exception ignored){}
        android.content.SharedPreferences.Editor pref=getPreferences(0).edit().putString("cloud_tree_uri",tree.toString());
        if(!pendingCloudProvider.isEmpty())pref.putString("cloud_provider",pendingCloudProvider);
        pref.apply();
        String provider=pendingCloudProvider.isEmpty()?"Cloud":pendingCloudProvider;
        pendingCloudProvider="";
        saveAutomaticBackup();
        toast(provider+"-Ordner eingerichtet. Die Sicherung wird ab jetzt automatisch aktualisiert.");
    }
    void saveAutomaticBackup(){
        try{
            String provider=getPreferences(0).getString("cloud_provider","");
            if("Google Drive".equals(provider)){
                if(googleAccessToken!=null&&System.currentTimeMillis()<googleTokenValidUntil)uploadGoogleDriveBackup();
                else authorizeGoogleDrive(false);
            }
            String json=backupJson();
            File local=new File(getFilesDir(),"Meine-Finanzen-Backup.json");
            try(Writer w=new OutputStreamWriter(new FileOutputStream(local),"UTF-8")){w.write(json);}
            String saved=getPreferences(0).getString("cloud_tree_uri",null);
            if(saved!=null)writeTreeBackup(Uri.parse(saved),json);
        }catch(Exception e){android.util.Log.e("MeineFinanzen","Automatisches Backup fehlgeschlagen",e);}
    }
    void writeTreeBackup(Uri tree,String json)throws Exception{
        String folderName="MeineFinanzen",fileName="Meine-Finanzen-Backup.json";Uri folderUri=null;
        String rootId=DocumentsContract.getTreeDocumentId(tree);
        Uri rootChildren=DocumentsContract.buildChildDocumentsUriUsingTree(tree,rootId);
        try(Cursor c=getContentResolver().query(rootChildren,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(c!=null)while(c.moveToNext())if(folderName.equals(c.getString(1))){folderUri=DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0));break;}
        }
        if(folderUri==null)folderUri=DocumentsContract.createDocument(getContentResolver(),tree,DocumentsContract.Document.MIME_TYPE_DIR,folderName);
        if(folderUri==null)throw new IOException("Der Cloud-Ordner MeineFinanzen konnte nicht erstellt werden.");
        String folderId=DocumentsContract.getDocumentId(folderUri);Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,folderId);Uri fileUri=null;
        try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(c!=null)while(c.moveToNext())if(fileName.equals(c.getString(1))){fileUri=DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0));break;}
        }
        if(fileUri==null)fileUri=DocumentsContract.createDocument(getContentResolver(),folderUri,"application/json",fileName);
        if(fileUri==null)throw new IOException("Backup-Datei konnte im Ordner MeineFinanzen nicht erstellt werden.");
        try(OutputStream out=getContentResolver().openOutputStream(fileUri,"wt")){if(out==null)throw new IOException("Cloud-Datei ist nicht beschreibbar.");out.write(json.getBytes("UTF-8"));out.flush();}
    }
    String backupJson()throws Exception{
        org.json.JSONArray aa=new org.json.JSONArray(),ee=new org.json.JSONArray();
        for(String a:db.accounts())aa.put(a);
        Cursor c=db.allEntries();
        while(c.moveToNext()){
            org.json.JSONObject o=new org.json.JSONObject();o.put("account",c.getString(1));o.put("purpose",c.getString(2));o.put("date",c.getString(3));o.put("amount",c.getDouble(4));o.put("income",c.getInt(5)==1);String receiptPath=c.getString(6);if(receiptPath!=null&&!receiptPath.isEmpty()&&new File(receiptPath).exists())o.put("receipt_image",android.util.Base64.encodeToString(readFileBytes(receiptPath),android.util.Base64.NO_WRAP));ee.put(o);
        }
        c.close();org.json.JSONObject root=new org.json.JSONObject();root.put("accounts",aa);root.put("entries",ee);root.put("backup_version",1);root.put("created_at",System.currentTimeMillis());return root.toString(2);
    }
    void writeBackup(Uri uri)throws Exception{
        try(Writer w=new OutputStreamWriter(getContentResolver().openOutputStream(uri),"UTF-8")){w.write(backupJson());}
        toast("Backup gespeichert.");
    }
    void readBackup(Uri uri)throws Exception{StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri),"UTF-8"))){String line;while((line=r.readLine())!=null)s.append(line);}restoreBackupJson(s.toString());}
    void restoreBackupJson(String json)throws Exception{org.json.JSONObject j=new org.json.JSONObject(json);org.json.JSONArray aa=j.getJSONArray("accounts"),ee=j.getJSONArray("entries");db.clearAll();for(int i=0;i<aa.length();i++)db.addAccount(aa.getString(i));for(int i=0;i<ee.length();i++){org.json.JSONObject o=ee.getJSONObject(i);int aid=db.accountId(o.getString("account"));if(aid>0){String receiptPath="";String image=o.optString("receipt_image","");if(!image.isEmpty())receiptPath=restoreReceiptImage(image);db.addEntry(aid,o.optString("purpose",""),o.getString("date"),o.getDouble("amount"),o.getBoolean("income"),receiptPath);}}ensureAccount();accountId=-1;reloadAccounts();refresh();toast("Backup wiederhergestellt.");}
    void showErrorDialog(String title,String detail){TextView message=text(detail,14,DARK,false);message.setTextIsSelectable(true);message.setPadding(dp(16),dp(12),dp(16),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(message);new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNeutralButton("Fehler kopieren",(d,w)->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Fehlermeldung",detail));toast("Fehlermeldung kopiert.");}).setPositiveButton("Schliessen",null).show();}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    static class DB extends SQLiteOpenHelper{
        DB(Context c){super(c,"finanzen.db",null,2);}
        public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE)");d.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT,account_id INTEGER,purpose TEXT,date TEXT,amount REAL,income INTEGER,receipt_path TEXT)");}
        public void onUpgrade(SQLiteDatabase d,int a,int b){if(a<2)d.execSQL("ALTER TABLE entries ADD COLUMN receipt_path TEXT");}
        void addAccount(String n){getWritableDatabase().execSQL("INSERT OR IGNORE INTO accounts(name) VALUES(?)",new Object[]{n});}
        void renameAccount(int id,String n){getWritableDatabase().execSQL("UPDATE accounts SET name=? WHERE id=?",new Object[]{n,id});}
        void deleteAccount(int id){getWritableDatabase().execSQL("DELETE FROM entries WHERE account_id=?",new Object[]{id});getWritableDatabase().execSQL("DELETE FROM accounts WHERE id=?",new Object[]{id});}
        void clearAll(){getWritableDatabase().execSQL("DELETE FROM entries");getWritableDatabase().execSQL("DELETE FROM accounts");}
        ArrayList<String> accounts(){ArrayList<String>a=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT name FROM accounts ORDER BY id",null)){while(c.moveToNext())a.add(c.getString(0));}return a;}
        int accountId(String n){try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM accounts WHERE name=?",new String[]{n})){return c.moveToFirst()?c.getInt(0):-1;}}
        String accountName(int id){try(Cursor c=getReadableDatabase().rawQuery("SELECT name FROM accounts WHERE id=?",new String[]{""+id})){return c.moveToFirst()?c.getString(0):"";}}
        int countAccounts(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM accounts",null)){return c.moveToFirst()?c.getInt(0):0;}}
        double accountBalance(int id){try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(CASE WHEN income=1 THEN amount ELSE -amount END),0) FROM entries WHERE account_id=?",new String[]{""+id})){return c.moveToFirst()?c.getDouble(0):0;}}
        void addEntry(int id,String p,String date,double amount,boolean income,String receipt){getWritableDatabase().execSQL("INSERT INTO entries(account_id,purpose,date,amount,income,receipt_path) VALUES(?,?,?,?,?,?)",new Object[]{id,p,date,amount,income?1:0,receipt});}
        void updateEntry(int id,String p,String date,double amount,boolean income,String receipt){getWritableDatabase().execSQL("UPDATE entries SET purpose=?,date=?,amount=?,income=?,receipt_path=? WHERE id=?",new Object[]{p,date,amount,income?1:0,receipt,id});}
        void deleteEntry(int id){getWritableDatabase().execSQL("DELETE FROM entries WHERE id=?",new Object[]{id});}
        Cursor one(int id){return getReadableDatabase().rawQuery("SELECT id,purpose,date,amount,income,receipt_path FROM entries WHERE id=?",new String[]{""+id});}
        Cursor entries(int aid,int month,int year){String sql="SELECT id,purpose,date,amount,income,receipt_path FROM entries WHERE account_id=? AND substr(date,7,4)=?"+(month==0?"":" AND substr(date,4,2)=?")+" ORDER BY substr(date,7,4) DESC,substr(date,4,2) DESC,substr(date,1,2) DESC";return month==0?getReadableDatabase().rawQuery(sql,new String[]{""+aid,""+year}):getReadableDatabase().rawQuery(sql,new String[]{""+aid,""+year,String.format(Locale.US,"%02d",month)});}
        Cursor allEntries(){return getReadableDatabase().rawQuery("SELECT e.id,a.name,e.purpose,e.date,e.amount,e.income,e.receipt_path FROM entries e JOIN accounts a ON a.id=e.account_id",null);}
    }
}
