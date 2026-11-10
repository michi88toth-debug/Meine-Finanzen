package ch.finanztracker.app;

import android.app.*;
import android.os.Bundle;
import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
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
    final int REQ_CSV=11, REQ_PDF=12, REQ_BACKUP=13, REQ_RESTORE=14, REQ_GOOGLE_DRIVE_SETUP=15, REQ_RECEIPT_CAMERA=17, REQ_RECEIPT_PDF=18, REQ_GOOGLE_DRIVE_RESTORE=19, REQ_RECEIPT_FILE=20;
    ArrayList<String[]> exportRows=new ArrayList<>();
    boolean needsUnlockOnResume=false;
    final ExecutorService cloudExecutor=Executors.newSingleThreadExecutor();
    String activeReceiptPath=""; String pendingReceiptCapturePath=""; String pendingReceiptPdfPath=""; TextView receiptAddButton;

    @Override public void onCreate(Bundle b){super.onCreate(b); getWindow().setStatusBarColor(0xFF126B43); getWindow().setNavigationBarColor(0xFFFFFFFF); getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR); db=new DB(this); ensureAccount(); buildUI(); checkPin();}

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
    void reloadAccounts(){accounts=db.accounts();ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,accounts);accountSpin.setAdapter(a);String savedName=getPreferences(0).getString("last_account_name","");String wanted=savedName;if(accountId>0){String current=db.accountName(accountId);if(!current.isEmpty())wanted=current;}int initial=accounts.indexOf(wanted);if(initial<0)initial=0;accountSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(pos>=0&&pos<accounts.size()){String name=accounts.get(pos);accountId=db.accountId(name);getPreferences(0).edit().putString("last_account_name",name).apply();refresh();}}});if(!accounts.isEmpty())accountSpin.setSelection(initial);else accountId=-1;}
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
            info.addView(text(purpose.isEmpty()?"Ohne Zweck":purpose,15,DARK,true));String time=c.getString(6)==null?"00:00":c.getString(6);info.addView(text(date+"  ·  "+time+" Uhr",12,0xFF718078,false));
            if(c.getColumnCount()>5){String receiptPath=c.getString(5);if(receiptPath!=null&&!receiptPath.isEmpty()&&new File(receiptPath).exists()){TextView receipt=text("🧾",17,DARK,true);LinearLayout.LayoutParams rp=lp(28,32);rp.rightMargin=dp(5);card.addView(receipt,rp);receipt.setOnClickListener(v->showReceipt(receiptPath));}}
            TextView val=text((income?"+ ":"− ")+money(amount),15,income?GREEN:RED,true);card.addView(val);
            card.setOnClickListener(v->confirmEdit(id,purpose,date,amount,income));
        }
        c.close();expenses.setText("Ausgaben\n"+money(exp));incomes.setText("Einnahmen\n"+money(inc));balance.setText("Saldo\n"+money(inc-exp));
    }
    String money(double n){return String.format(Locale.GERMANY,"CHF %,.2f",n);}
    void entryDialog(boolean income,int[] existing){
        final EditText purpose=new EditText(this),amount=new EditText(this);purpose.setHint("Zweck (optional)");amount.setHint("Betrag in CHF");amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),0);
        box.addView(purpose);box.addView(amount);activeReceiptPath="";
        final Calendar cal=Calendar.getInstance();final TextView date=text("Datum: "+new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).format(cal.getTime())+"  ✎",15,GREEN,true);date.setPadding(0,dp(14),0,dp(8));box.addView(date);final TextView time=text("Uhrzeit: "+new SimpleDateFormat("HH:mm",Locale.GERMANY).format(cal.getTime())+"  ✎",15,GREEN,true);time.setPadding(0,dp(4),0,dp(8));box.addView(time);
        date.setOnClickListener(v->new DatePickerDialog(this,(view,yy,mm,dd)->{cal.set(yy,mm,dd);date.setText("Datum: "+String.format(Locale.GERMANY,"%02d.%02d.%04d",dd,mm+1,yy)+"  ✎");},cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show());time.setOnClickListener(v->new TimePickerDialog(this,(view,hh,mi)->{cal.set(Calendar.HOUR_OF_DAY,hh);cal.set(Calendar.MINUTE,mi);time.setText("Uhrzeit: "+String.format(Locale.GERMANY,"%02d:%02d",hh,mi)+"  ✎");},cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true).show());
        receiptAddButton=text("＋  Quittung hinzufügen",14,GREEN,true);receiptAddButton.setPadding(0,dp(10),0,dp(10));box.addView(receiptAddButton);receiptAddButton.setOnClickListener(v->receiptChoiceDialog());
        if(existing!=null){Cursor e=db.one(existing[0]);if(e.moveToFirst()){purpose.setText(e.getString(1));amount.setText(""+e.getDouble(3));activeReceiptPath=e.getString(5)==null?"":e.getString(5);if(!activeReceiptPath.isEmpty()&&new File(activeReceiptPath).exists())receiptAddButton.setText("🧾  Quittung ändern / hinzufügen");try{cal.setTime(new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).parse(e.getString(2)));}catch(Exception ignored){}String savedTime=e.getString(6)==null?"00:00":e.getString(6);try{String[] hm=savedTime.split(":");cal.set(Calendar.HOUR_OF_DAY,Integer.parseInt(hm[0]));cal.set(Calendar.MINUTE,Integer.parseInt(hm[1]));}catch(Exception ignored){}date.setText("Datum: "+e.getString(2)+"  ✎");time.setText("Uhrzeit: "+savedTime+"  ✎");}e.close();}
        new AlertDialog.Builder(this).setTitle(income?"Einnahme erfassen":"Ausgabe erfassen").setView(box).setNegativeButton("Abbrechen",null).setPositiveButton(existing==null?"Speichern":"Änderungen speichern",(d,w)->{
            String p=purpose.getText().toString().trim();String a=amount.getText().toString().trim().replace(",",".");if(a.isEmpty()){toast("Bitte einen Betrag eingeben.");return;}
            try{double n=Double.parseDouble(a);if(n<=0)throw new Exception();String dt=new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).format(cal.getTime());String tm=new SimpleDateFormat("HH:mm",Locale.GERMANY).format(cal.getTime());if(existing==null)db.addEntry(accountId,p,dt,tm,n,income,activeReceiptPath);else db.updateEntry(existing[0],p,dt,tm,n,income,activeReceiptPath);refresh();}catch(Exception ex){toast("Bitte einen gültigen Betrag eingeben.");}
        }).show();
    }
    void captureReceipt(){
        try{File dir=new File(getFilesDir(),"receipts");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Quittungsordner konnte nicht erstellt werden.");File photo=new File(dir,"receipt_"+System.currentTimeMillis()+".jpg");pendingReceiptCapturePath=photo.getAbsolutePath();Uri uri=androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".fileprovider",photo);Intent camera=new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);camera.putExtra(android.provider.MediaStore.EXTRA_OUTPUT,uri);camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);camera.setClipData(ClipData.newUri(getContentResolver(),"Quittung",uri));if(camera.resolveActivity(getPackageManager())!=null)startActivityForResult(camera,REQ_RECEIPT_CAMERA);else{pendingReceiptCapturePath="";toast("Keine Kamera-App gefunden.");}}catch(Exception e){pendingReceiptCapturePath="";showErrorDialog("Kamera konnte nicht geöffnet werden",e.toString());}
    }
    void compressReceipt(File file)throws Exception{BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);int sample=1;while(bounds.outWidth/sample>2000||bounds.outHeight/sample>2000)sample*=2;BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(bitmap==null)throw new IOException("Foto konnte nicht gelesen werden.");int max=1800;float scale=Math.min(1f,Math.min((float)max/bitmap.getWidth(),(float)max/bitmap.getHeight()));if(scale<1f){Bitmap scaled=Bitmap.createScaledBitmap(bitmap,Math.round(bitmap.getWidth()*scale),Math.round(bitmap.getHeight()*scale),true);bitmap.recycle();bitmap=scaled;}try(FileOutputStream out=new FileOutputStream(file,false)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,78,out))throw new IOException("Foto konnte nicht komprimiert werden.");out.flush();}bitmap.recycle();}
    void showReceipt(String path){
        try{
            if(path.toLowerCase(Locale.ROOT).endsWith(".pdf")){showReceiptPdf(path);return;}
            Bitmap bitmap=BitmapFactory.decodeFile(path);if(bitmap==null)throw new IOException("Quittungsbild kann nicht geöffnet werden.");
            ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ScrollView scroll=new ScrollView(this);scroll.addView(image);
            new AlertDialog.Builder(this).setTitle("Quittung").setView(scroll).setNegativeButton("Schliessen",null).setPositiveButton("Als PDF speichern",(d,w)->{pendingReceiptPdfPath=path;Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT);save.addCategory(Intent.CATEGORY_OPENABLE);save.setType("application/pdf");save.putExtra(Intent.EXTRA_TITLE,"Quittung.pdf");startActivityForResult(save,REQ_RECEIPT_PDF);}).show();
        }catch(Exception e){showErrorDialog("Quittung konnte nicht geöffnet werden",e.toString());}
    }
    void showReceiptPdf(String path)throws Exception{
        ParcelFileDescriptor descriptor=ParcelFileDescriptor.open(new File(path),ParcelFileDescriptor.MODE_READ_ONLY);
        PdfRenderer renderer=new PdfRenderer(descriptor);LinearLayout pages=column();pages.setPadding(dp(8),dp(8),dp(8),dp(8));
        for(int i=0;i<renderer.getPageCount();i++){
            PdfRenderer.Page page=renderer.openPage(i);int width=Math.max(1,page.getWidth()),height=Math.max(1,page.getHeight());
            float scale=Math.min(1f,900f/width);Bitmap bitmap=Bitmap.createBitmap(Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale)),Bitmap.Config.ARGB_8888);
            page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);page.close();
            ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(8);pages.addView(image,p);
        }
        int count=renderer.getPageCount();renderer.close();descriptor.close();ScrollView scroll=new ScrollView(this);scroll.addView(pages);
        new AlertDialog.Builder(this).setTitle("Quittung (PDF, "+count+" Seite"+(count==1?"":"n")+")").setView(scroll).setPositiveButton("Schliessen",null).show();
    }
    void receiptChoiceDialog(){
        new AlertDialog.Builder(this).setTitle("Quittung hinzufügen").setItems(new String[]{"Kamera","Von Gerät"},(d,w)->{if(w==0)captureReceipt();else chooseReceiptFile();}).show();
    }
    void chooseReceiptFile(){
        Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT);pick.addCategory(Intent.CATEGORY_OPENABLE);pick.setType("*/*");pick.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","application/pdf"});pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(pick,REQ_RECEIPT_FILE);
    }
    String receiptDisplayName(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}
        return "Quittung";
    }
    void importReceiptFile(Uri uri)throws Exception{
        String mime=getContentResolver().getType(uri);String name=receiptDisplayName(uri);String lower=(name==null?"":name).toLowerCase(Locale.ROOT);
        boolean pdf="application/pdf".equalsIgnoreCase(mime)||lower.endsWith(".pdf");
        boolean image=(mime!=null&&mime.startsWith("image/"))||lower.matches(".*\\.(jpg|jpeg|png|webp|heic|heif|bmp)$");
        if(!pdf&&!image)throw new IOException("Bitte eine PDF- oder Bilddatei auswählen.");
        String ext=pdf?".pdf":(lower.matches(".*\\.[a-z0-9]{2,5}$")?lower.substring(lower.lastIndexOf('.')):".jpg");
        File dir=new File(getFilesDir(),"receipts");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Quittungsordner konnte nicht erstellt werden.");
        File target=new File(dir,"receipt_import_"+System.currentTimeMillis()+ext);
        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(target)){
            if(in==null)throw new IOException("Datei konnte nicht geöffnet werden.");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.flush();
        }
        if(image&&ext.equals(".jpg")){try{compressReceipt(target);}catch(Exception ignored){}}
        activeReceiptPath=target.getAbsolutePath();if(receiptAddButton!=null)receiptAddButton.setText("🧾  Quittung hinzugefügt");toast("Quittung hinzugefügt.");
    }
    void writeReceiptPdf(Uri uri)throws Exception{Bitmap bitmap=BitmapFactory.decodeFile(pendingReceiptPdfPath);if(bitmap==null)throw new IOException("Quittungsbild ist nicht verfügbar.");PdfDocument pdf=new PdfDocument();PdfDocument.PageInfo info=new PdfDocument.PageInfo.Builder(595,842,1).create();PdfDocument.Page page=pdf.startPage(info);Canvas canvas=page.getCanvas();Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);float scale=Math.min(535f/bitmap.getWidth(),782f/bitmap.getHeight());float width=bitmap.getWidth()*scale,height=bitmap.getHeight()*scale;float left=(595-width)/2f,top=(842-height)/2f;canvas.drawBitmap(bitmap,null,new RectF(left,top,left+width,top+height),paint);pdf.finishPage(page);try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("PDF-Datei kann nicht geschrieben werden.");pdf.writeTo(out);}pdf.close();bitmap.recycle();pendingReceiptPdfPath="";toast("Quittung als PDF gespeichert.");}
    byte[] readFileBytes(String path)throws Exception{try(InputStream in=new FileInputStream(path);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
    String restoreReceiptImage(String encoded)throws Exception{byte[] bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);File dir=new File(getFilesDir(),"receipts");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Quittungsordner konnte nicht erstellt werden.");File file=new File(dir,"restored_"+System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8)+".jpg");try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.flush();}return file.getAbsolutePath();}
    String restoreReceiptFile(String encoded,String originalName)throws Exception{
        byte[] bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);File dir=new File(getFilesDir(),"receipts");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Quittungsordner konnte nicht erstellt werden.");
        String ext=".jpg";if(originalName!=null){String n=originalName.toLowerCase(Locale.ROOT);if(n.endsWith(".pdf"))ext=".pdf";else if(n.matches(".*\\.(png|webp|heic|heif|bmp|jpeg)$"))ext=n.substring(n.lastIndexOf('.')); }
        File file=new File(dir,"restored_"+System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8)+ext);try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.flush();}return file.getAbsolutePath();
    }
    void confirmEdit(int id,String p,String date,double amt,boolean income){
        new AlertDialog.Builder(this).setMessage("Willst du den Eintrag bearbeiten?").setNegativeButton("Nein",null).setPositiveButton("Ja",(d,w)->new AlertDialog.Builder(this).setTitle("Eintrag bearbeiten").setItems(new String[]{"Bearbeiten","Löschen","Abbrechen"},(x,which)->{if(which==0)entryDialog(income,new int[]{id});else if(which==1)new AlertDialog.Builder(this).setMessage("Eintrag wirklich löschen?").setNegativeButton("Nein",null).setPositiveButton("Löschen",(a,b)->{db.deleteEntry(id);refresh();}).show();}).show()).show();
    }
    void exportDialog(){new AlertDialog.Builder(this).setTitle("Exportformat wählen").setItems(new String[]{"PDF (A4)","Excel (CSV)"},(d,w)->{exportRows.clear();Cursor c=db.entries(accountId,selectedMonth,selectedYear);while(c.moveToNext())exportRows.add(new String[]{(c.getString(1)==null||c.getString(1).trim().isEmpty())?"Ohne Zweck":c.getString(1),c.getString(2)+" "+(c.getString(6)==null?"00:00":c.getString(6)),String.format(Locale.US,"%.2f",c.getDouble(3)),c.getInt(4)==1?"Einnahme":"Ausgabe"});c.close();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType(w==0?"application/pdf":"text/csv");i.putExtra(Intent.EXTRA_TITLE,w==0?"Meine-Finanzen.pdf":"Meine-Finanzen.csv");startActivityForResult(i,w==0?REQ_PDF:REQ_CSV);}).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==REQ_RECEIPT_CAMERA){if(result==RESULT_OK&&!pendingReceiptCapturePath.isEmpty()){File f=new File(pendingReceiptCapturePath);if(f.exists()&&f.length()>0){try{compressReceipt(f);activeReceiptPath=pendingReceiptCapturePath;if(receiptAddButton!=null)receiptAddButton.setText("🧾  Quittung hinzugefügt");toast("Quittung aufgenommen.");}catch(Exception ex){showErrorDialog("Quittung konnte nicht verarbeitet werden",ex.toString());}}else toast("Kein Quittungsfoto erhalten.");}else toast("Fotoaufnahme abgebrochen.");pendingReceiptCapturePath="";return;}
        if(result!=RESULT_OK||data==null)return;
        try{Uri u=data.getData();if(request==REQ_CSV)writeCsv(u);else if(request==REQ_PDF)writePdf(u);else if(request==REQ_BACKUP)writeBackup(u);else if(request==REQ_RESTORE)readBackup(u);else if(request==REQ_GOOGLE_DRIVE_SETUP)saveGoogleDriveBackupFile(data);else if(request==REQ_GOOGLE_DRIVE_RESTORE)restoreGoogleDriveBackupFile(data);else if(request==REQ_RECEIPT_PDF)writeReceiptPdf(u);else if(request==REQ_RECEIPT_FILE)importReceiptFile(u);}
        catch(Exception e){toast("Datei konnte nicht verarbeitet werden: "+e.getMessage());}
    }
    void writeCsv(Uri uri)throws Exception{try(Writer w=new OutputStreamWriter(getContentResolver().openOutputStream(uri),"UTF-8")){w.write("\uFEFFZweck;Datum / Uhrzeit;Betrag CHF;Art\n");for(String[]r:exportRows)w.write(csv(r[0])+";"+r[1]+";"+r[2].replace(".",",")+";"+r[3]+"\n");}toast("Excel-Datei gespeichert.");}
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
    void accountsDialog(){String[]opts={"Konto hinzufügen","Konto umbenennen","Konto löschen"};new AlertDialog.Builder(this).setTitle("Konten verwalten").setItems(opts,(d,w)->{if(w==0)askText("Neues Konto",name->{db.addAccount(name);getPreferences(0).edit().putString("last_account_name",name).apply();accountId=-1;reloadAccounts();refresh();});else if(w==1)askText("Neuer Kontoname",name->{db.renameAccount(accountId,name);getPreferences(0).edit().putString("last_account_name",name).apply();reloadAccounts();refresh();});else new AlertDialog.Builder(this).setMessage("Konto und alle zugehörigen Einträge löschen?").setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(a,b)->{db.deleteAccount(accountId);ensureAccount();accountId=-1;reloadAccounts();refresh();}).show();}).show();}
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
        if(enabled){
            getPreferences(0).edit().putBoolean("bio_on",false).apply();
            toast("Fingerabdruck-Anmeldung deaktiviert.");
            return;
        }
        BiometricManager manager=BiometricManager.from(this);
        if(manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){
            toast("Auf diesem Gerät ist kein verwendbarer Fingerabdruck eingerichtet.");
            return;
        }
        getPreferences(0).edit().putBoolean("bio_on",true).apply();
        toast("Fingerabdruck-Anmeldung aktiviert.");
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
        boolean connected=getPreferences(0).getString("google_drive_backup_uri",null)!=null;
        String[] opts={"Backup auf Gerät speichern","Backup importieren / wiederherstellen","Google Drive-Backup einrichten / ändern","Google Drive-Backup wiederherstellen","Google Drive-Backup deaktivieren"};
        new AlertDialog.Builder(this).setTitle("Datensicherung").setItems(opts,(d,w)->{
            if(w==0){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"Meine-Finanzen-Backup.json");startActivityForResult(i,REQ_BACKUP);}
            else if(w==1){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");startActivityForResult(i,REQ_RESTORE);}
            else if(w==2)chooseGoogleDriveBackupFile();
            else if(w==3){
                String saved=getPreferences(0).getString("google_drive_backup_uri",null);
                if(saved!=null){try{readBackup(Uri.parse(saved));}catch(Exception e){showErrorDialog("Google-Drive-Backup konnte nicht gelesen werden",e.toString());}}
                else chooseGoogleDriveBackupForRestore();
            }else if(w==4){
                String saved=getPreferences(0).getString("google_drive_backup_uri",null);
                if(saved!=null){try{getContentResolver().releasePersistableUriPermission(Uri.parse(saved),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}}
                getPreferences(0).edit().remove("google_drive_backup_uri").remove("google_drive_backup_fingerprint").apply();
                toast("Google-Drive-Backup-Verbindung entfernt. Die lokale automatische Sicherung bleibt aktiv.");
            }
        }).show();
    }
    void chooseGoogleDriveBackupFile(){
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE,"Meine-Finanzen-Backup.json");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,REQ_GOOGLE_DRIVE_SETUP);
    }
    void chooseGoogleDriveBackupForRestore(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,REQ_GOOGLE_DRIVE_RESTORE);
    }
    void keepBackupUriPermission(Uri uri,Intent data)throws Exception{
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if((data.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)==0)
            throw new IOException("Der Speicheranbieter hat keinen dauerhaft speicherbaren Dateizugriff angeboten. Bitte Google Drive im Dateiauswahldialog auswählen und erneut versuchen.");
        getContentResolver().takePersistableUriPermission(uri,flags);
    }
    void saveGoogleDriveBackupFile(Intent data){
        Uri uri=data.getData();if(uri==null)return;
        try{
            keepBackupUriPermission(uri,data);
            getPreferences(0).edit().putString("google_drive_backup_uri",uri.toString()).remove("google_drive_backup_fingerprint").apply();
            String json=backupJson();
            writeGoogleDriveBackup(uri,json);
            getPreferences(0).edit().putString("google_drive_backup_fingerprint",backupFingerprint(json)).apply();
            toast("Google Drive verbunden. Das automatische Backup ist eingerichtet.");
        }catch(Exception e){showErrorDialog("Google-Drive-Backup konnte nicht eingerichtet werden",e.toString());}
    }
    void restoreGoogleDriveBackupFile(Intent data){
        Uri uri=data.getData();if(uri==null)return;
        try{
            keepBackupUriPermission(uri,data);
            getPreferences(0).edit().putString("google_drive_backup_uri",uri.toString()).remove("google_drive_backup_fingerprint").apply();
            readBackup(uri);
            String json=backupJson();
            getPreferences(0).edit().putString("google_drive_backup_fingerprint",backupFingerprint(json)).apply();
            toast("Google-Drive-Backup übernommen. Künftige Sicherungen erfolgen automatisch.");
        }catch(Exception e){showErrorDialog("Google-Drive-Backup konnte nicht wiederhergestellt werden",e.toString());}
    }
    void saveAutomaticBackup(){
        try{
            String json=backupJson();
            File local=new File(getFilesDir(),"Meine-Finanzen-Backup.json");
            try(Writer w=new OutputStreamWriter(new FileOutputStream(local),"UTF-8")){w.write(json);}
            String saved=getPreferences(0).getString("google_drive_backup_uri",null);
            if(saved!=null){
                String fingerprint=backupFingerprint(json);
                String lastFingerprint=getPreferences(0).getString("google_drive_backup_fingerprint","");
                if(!fingerprint.equals(lastFingerprint)){
                    writeGoogleDriveBackup(Uri.parse(saved),json);
                    getPreferences(0).edit().putString("google_drive_backup_fingerprint",fingerprint).apply();
                }
            }
        }catch(Exception e){android.util.Log.e("MeineFinanzen","Automatisches Backup fehlgeschlagen",e);toast("Automatisches Backup fehlgeschlagen. Bitte Google-Drive-Verbindung prüfen.");}
    }
    String backupFingerprint(String json)throws Exception{
        org.json.JSONObject data=new org.json.JSONObject(json);
        data.remove("created_at");
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes("UTF-8"));
        return android.util.Base64.encodeToString(digest,android.util.Base64.NO_WRAP);
    }
    void writeGoogleDriveBackup(Uri uri,String json)throws Exception{
        try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
            if(out==null)throw new IOException("Google Drive hat die Backup-Datei nicht zum Schreiben geöffnet.");
            out.write(json.getBytes("UTF-8"));
            out.flush();
        }
    }
    String backupJson()throws Exception{
        org.json.JSONArray aa=new org.json.JSONArray(),ee=new org.json.JSONArray();
        for(String a:db.accounts())aa.put(a);
        Cursor c=db.allEntries();
        while(c.moveToNext()){
            org.json.JSONObject o=new org.json.JSONObject();o.put("account",c.getString(1));o.put("purpose",c.getString(2));o.put("date",c.getString(3));o.put("time",c.getString(7)==null?"00:00":c.getString(7));o.put("amount",c.getDouble(4));o.put("income",c.getInt(5)==1);String receiptPath=c.getString(6);if(receiptPath!=null&&!receiptPath.isEmpty()&&new File(receiptPath).exists()){File receiptFile=new File(receiptPath);o.put("receipt_file",android.util.Base64.encodeToString(readFileBytes(receiptPath),android.util.Base64.NO_WRAP));o.put("receipt_name",receiptFile.getName());}ee.put(o);
        }
        c.close();org.json.JSONObject root=new org.json.JSONObject();root.put("accounts",aa);root.put("entries",ee);root.put("backup_version",1);root.put("created_at",System.currentTimeMillis());return root.toString(2);
    }
    void writeBackup(Uri uri)throws Exception{
        try(Writer w=new OutputStreamWriter(getContentResolver().openOutputStream(uri),"UTF-8")){w.write(backupJson());}
        toast("Backup gespeichert.");
    }
    void readBackup(Uri uri)throws Exception{StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri),"UTF-8"))){String line;while((line=r.readLine())!=null)s.append(line);}restoreBackupJson(s.toString());}
    void restoreBackupJson(String json)throws Exception{org.json.JSONObject j=new org.json.JSONObject(json);org.json.JSONArray aa=j.getJSONArray("accounts"),ee=j.getJSONArray("entries");db.clearAll();for(int i=0;i<aa.length();i++)db.addAccount(aa.getString(i));for(int i=0;i<ee.length();i++){org.json.JSONObject o=ee.getJSONObject(i);int aid=db.accountId(o.getString("account"));if(aid>0){String receiptPath="";String fileData=o.optString("receipt_file","");if(!fileData.isEmpty())receiptPath=restoreReceiptFile(fileData,o.optString("receipt_name","Quittung"));else{String image=o.optString("receipt_image","");if(!image.isEmpty())receiptPath=restoreReceiptImage(image);}db.addEntry(aid,o.optString("purpose",""),o.getString("date"),o.optString("time","00:00"),o.getDouble("amount"),o.getBoolean("income"),receiptPath);}}ensureAccount();accountId=-1;reloadAccounts();refresh();toast("Backup wiederhergestellt.");}
    void showErrorDialog(String title,String detail){TextView message=text(detail,14,DARK,false);message.setTextIsSelectable(true);message.setPadding(dp(16),dp(12),dp(16),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(message);new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNeutralButton("Fehler kopieren",(d,w)->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Fehlermeldung",detail));toast("Fehlermeldung kopiert.");}).setPositiveButton("Schliessen",null).show();}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    static class DB extends SQLiteOpenHelper{
        DB(Context c){super(c,"finanzen.db",null,3);}
        public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE)");d.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT,account_id INTEGER,purpose TEXT,date TEXT,amount REAL,income INTEGER,receipt_path TEXT,entry_time TEXT DEFAULT '00:00')");}
        public void onUpgrade(SQLiteDatabase d,int a,int b){if(a<2)d.execSQL("ALTER TABLE entries ADD COLUMN receipt_path TEXT");if(a<3)d.execSQL("ALTER TABLE entries ADD COLUMN entry_time TEXT DEFAULT '00:00'");}
        void addAccount(String n){getWritableDatabase().execSQL("INSERT OR IGNORE INTO accounts(name) VALUES(?)",new Object[]{n});}
        void renameAccount(int id,String n){getWritableDatabase().execSQL("UPDATE accounts SET name=? WHERE id=?",new Object[]{n,id});}
        void deleteAccount(int id){getWritableDatabase().execSQL("DELETE FROM entries WHERE account_id=?",new Object[]{id});getWritableDatabase().execSQL("DELETE FROM accounts WHERE id=?",new Object[]{id});}
        void clearAll(){getWritableDatabase().execSQL("DELETE FROM entries");getWritableDatabase().execSQL("DELETE FROM accounts");}
        ArrayList<String> accounts(){ArrayList<String>a=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT name FROM accounts ORDER BY id",null)){while(c.moveToNext())a.add(c.getString(0));}return a;}
        int accountId(String n){try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM accounts WHERE name=?",new String[]{n})){return c.moveToFirst()?c.getInt(0):-1;}}
        String accountName(int id){try(Cursor c=getReadableDatabase().rawQuery("SELECT name FROM accounts WHERE id=?",new String[]{""+id})){return c.moveToFirst()?c.getString(0):"";}}
        int countAccounts(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM accounts",null)){return c.moveToFirst()?c.getInt(0):0;}}
        double accountBalance(int id){try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(CASE WHEN income=1 THEN amount ELSE -amount END),0) FROM entries WHERE account_id=?",new String[]{""+id})){return c.moveToFirst()?c.getDouble(0):0;}}
        void addEntry(int id,String p,String date,String time,double amount,boolean income,String receipt){getWritableDatabase().execSQL("INSERT INTO entries(account_id,purpose,date,amount,income,receipt_path,entry_time) VALUES(?,?,?,?,?,?,?)",new Object[]{id,p,date,amount,income?1:0,receipt,time});}
        void updateEntry(int id,String p,String date,String time,double amount,boolean income,String receipt){getWritableDatabase().execSQL("UPDATE entries SET purpose=?,date=?,amount=?,income=?,receipt_path=?,entry_time=? WHERE id=?",new Object[]{p,date,amount,income?1:0,receipt,time,id});}
        void deleteEntry(int id){getWritableDatabase().execSQL("DELETE FROM entries WHERE id=?",new Object[]{id});}
        Cursor one(int id){return getReadableDatabase().rawQuery("SELECT id,purpose,date,amount,income,receipt_path,entry_time FROM entries WHERE id=?",new String[]{""+id});}
        Cursor entries(int aid,int month,int year){String sql="SELECT id,purpose,date,amount,income,receipt_path,entry_time FROM entries WHERE account_id=? AND substr(date,7,4)=?"+(month==0?"":" AND substr(date,4,2)=?")+" ORDER BY substr(date,7,4) DESC,substr(date,4,2) DESC,substr(date,1,2) DESC,entry_time DESC,id DESC";return month==0?getReadableDatabase().rawQuery(sql,new String[]{""+aid,""+year}):getReadableDatabase().rawQuery(sql,new String[]{""+aid,""+year,String.format(Locale.US,"%02d",month)});}
        Cursor allEntries(){return getReadableDatabase().rawQuery("SELECT e.id,a.name,e.purpose,e.date,e.amount,e.income,e.receipt_path,e.entry_time FROM entries e JOIN accounts a ON a.id=e.account_id",null);}
    }
}
