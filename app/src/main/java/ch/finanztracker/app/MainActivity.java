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
    DB db; LinearLayout root, list; Spinner accountSpin, monthSpin, yearSpin, sortSpin;
    TextView expenses, incomes, balance; ArrayList<String> accounts=new ArrayList<>(); int accountId=-1;
    String[] months={"Januar","Februar","März","April","Mai","Juni","Juli","August","September","Oktober","November","Dezember","Ganzes Jahr"};
    int selectedMonth=Calendar.getInstance().get(Calendar.MONTH)+1, selectedYear=Calendar.getInstance().get(Calendar.YEAR);
    final int REQ_CSV=11, REQ_PDF=12, REQ_BACKUP=13, REQ_RESTORE=14, REQ_GOOGLE_DRIVE_SETUP=15, REQ_RECEIPT_CAMERA=17, REQ_RECEIPT_PDF=18, REQ_GOOGLE_DRIVE_RESTORE=19, REQ_RECEIPT_FILE=20;
    ArrayList<String[]> exportRows=new ArrayList<>();
    boolean needsUnlockOnResume=false;
    final ExecutorService cloudExecutor=Executors.newSingleThreadExecutor();
    String activeReceiptPath=""; String pendingReceiptCapturePath=""; String pendingReceiptPdfPath=""; TextView receiptAddButton;

    @Override public void onCreate(Bundle b){super.onCreate(b); getWindow().setStatusBarColor(0xFF126B43); getWindow().setNavigationBarColor(0xFFFFFFFF); getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR); db=new DB(this); ensureAccount(); buildUI(); checkPin();}

    @Override protected void onStop(){super.onStop();needsUnlockOnResume=true;scheduleAutomaticBackup();}
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
        monthSpin=new Spinner(this);yearSpin=new Spinner(this);LinearLayout.LayoutParams fp=lp(0,48);fp.weight=1.45f;fp.rightMargin=dp(4);filters.addView(monthSpin,fp);LinearLayout.LayoutParams yp=lp(0,48);yp.weight=.8f;yp.leftMargin=dp(4);filters.addView(yearSpin,yp);
        monthSpin.setDropDownWidth(getResources().getDisplayMetrics().widthPixels-dp(32));yearSpin.setDropDownWidth(dp(120));
        FunnelIconView filterButton=new FunnelIconView();filterButton.setBackground(shape(0xFFFFFFFF,14));filterButton.setContentDescription("Sortierung filtern");LinearLayout.LayoutParams filterParams=lp(48,48);filterParams.leftMargin=dp(8);filters.addView(filterButton,filterParams);filterButton.setOnClickListener(v->showSortFilterDialog());
        setupFilters();
        ScrollView sc=new ScrollView(this);sc.setFillViewport(false);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        list=column();list.setPadding(dp(16),dp(2),dp(16),dp(12));sc.addView(list);
        LinearLayout exportWrap=row();exportWrap.setPadding(dp(16),dp(4),dp(16),dp(8));root.addView(exportWrap,lp(-1,56));Button export=button("Export",0xFF356B9A),insertFixed=button("Fixkosten einfügen",GREEN);LinearLayout.LayoutParams ep=lp(0,48);ep.weight=1;ep.rightMargin=dp(5);exportWrap.addView(export,ep);LinearLayout.LayoutParams ip=lp(0,48);ip.weight=1;ip.leftMargin=dp(5);exportWrap.addView(insertFixed,ip);export.setOnClickListener(v->exportDialog());insertFixed.setOnClickListener(v->insertFixedCosts());
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
    void reloadAccounts(){accounts=db.accounts();ArrayAdapter<String>a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,accounts);accountSpin.setAdapter(a);String savedName=getPreferences(0).getString("last_account_name","");String wanted=savedName;if(accountId>0){String current=db.accountName(accountId);if(!current.isEmpty())wanted=current;}int initial=accounts.indexOf(wanted);if(initial<0)initial=0;accountSpin.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(pos>=0&&pos<accounts.size()){String name=accounts.get(pos);accountId=db.accountId(name);getPreferences(0).edit().putString("last_account_name",name).apply();refresh();}}});if(!accounts.isEmpty()){accountSpin.setSelection(initial);accountId=db.accountId(accounts.get(initial));}else accountId=-1;}
    class FunnelIconView extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        FunnelIconView(){super(MainActivity.this);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float cx=getWidth()/2f,top=getHeight()*.28f,left=getWidth()*.25f,right=getWidth()*.75f;paint.setColor(GREEN);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2.2f));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);Path p=new Path();p.moveTo(left,top);p.lineTo(right,top);p.lineTo(cx+dp(4),getHeight()*.53f);p.lineTo(cx+dp(4),getHeight()*.68f);p.lineTo(cx-dp(4),getHeight()*.76f);p.lineTo(cx-dp(4),getHeight()*.53f);p.close();canvas.drawPath(p,paint);}
    }
    void showSortFilterDialog(){
        int selected=getPreferences(0).getInt("entry_sort_"+accountId,0);
        roundedOptionsDialog("Einträge filtern",
            new String[]{"Neueste zuerst","Älteste zuerst"},
            new String[]{"Nach Datum und Uhrzeit absteigend sortieren","Nach Datum und Uhrzeit aufsteigend sortieren"},
            new String[]{"↓","↑"},
            new int[]{0xFFE4F2E9,0xFFEAF1FA},
            selected,
            choice->{if(accountId>0)getPreferences(0).edit().putInt("entry_sort_"+accountId,choice==1?1:0).apply();refresh();});
    }
    void refresh(){
        if(list==null)return;list.removeAllViews();if(accountId<0&& !accounts.isEmpty())accountId=db.accountId(accounts.get(0));
        double inc=0,exp=0,transfer=0;
        if(selectedMonth>0){
            transfer=db.balanceBeforeMonth(accountId,selectedYear,selectedMonth);
            Calendar previous=Calendar.getInstance();previous.set(selectedYear,selectedMonth-2,1);
            String previousLabel=months[previous.get(Calendar.MONTH)];
            if(selectedMonth==1)previousLabel+=" "+previous.get(Calendar.YEAR);
            LinearLayout transferCard=row();transferCard.setBackground(shape(0xFFEAF1FA,12));transferCard.setPadding(dp(12),dp(12),dp(12),dp(12));
            LinearLayout.LayoutParams transferParams=lp(-1,-2);transferParams.bottomMargin=dp(8);list.addView(transferCard,transferParams);
            View transferStripe=new View(this);transferStripe.setBackgroundColor(BLUE);transferCard.addView(transferStripe,lp(4,42));
            TextView transferLabel=text("Übertrag Saldo ("+previousLabel+")",14,DARK,true);
            LinearLayout.LayoutParams transferTextParams=new LinearLayout.LayoutParams(0,-2,1);transferTextParams.leftMargin=dp(10);transferCard.addView(transferLabel,transferTextParams);
            transferCard.addView(text(money(transfer),15,BLUE,true));
        }
        Cursor c=db.entries(accountId,selectedMonth,selectedYear,getPreferences(0).getInt("entry_sort_"+accountId,0));
        if(c.getCount()==0&&selectedMonth==0){TextView empty=text("Noch keine Einträge in diesem Zeitraum.",14,0xFF77847D,false);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(35),0,dp(35));list.addView(empty);}
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
        c.close();expenses.setText("Ausgaben\n"+money(exp));incomes.setText("Einnahmen\n"+money(inc));balance.setText("Saldo\n"+money(transfer+inc-exp));
    }
    String money(double n){return String.format(Locale.GERMANY,"CHF %,.2f",n);}
    ScrollView scrollableForm(View content){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setSmoothScrollingEnabled(true);
        scroll.setVerticalScrollBarEnabled(true);scroll.addView(content,new ScrollView.LayoutParams(-1,-2));return scroll;
    }
    void enableDialogKeyboardResize(AlertDialog dialog){
        if(dialog!=null&&dialog.getWindow()!=null)dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
    void entryDialog(boolean income,int[] existing){
        final EditText purpose=new EditText(this),amount=new EditText(this);purpose.setHint("Zweck (optional)");amount.setHint("Betrag in CHF");amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),dp(8));
        box.addView(purpose);box.addView(amount);activeReceiptPath="";
        final Calendar cal=Calendar.getInstance();final TextView date=text("Datum: "+new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).format(cal.getTime())+"  ✎",15,GREEN,true);date.setPadding(0,dp(14),0,dp(8));box.addView(date);final TextView time=text("Uhrzeit: "+new SimpleDateFormat("HH:mm",Locale.GERMANY).format(cal.getTime())+"  ✎",15,GREEN,true);time.setPadding(0,dp(4),0,dp(8));box.addView(time);
        date.setOnClickListener(v->new DatePickerDialog(this,(view,yy,mm,dd)->{cal.set(yy,mm,dd);date.setText("Datum: "+String.format(Locale.GERMANY,"%02d.%02d.%04d",dd,mm+1,yy)+"  ✎");},cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show());time.setOnClickListener(v->new TimePickerDialog(this,(view,hh,mi)->{cal.set(Calendar.HOUR_OF_DAY,hh);cal.set(Calendar.MINUTE,mi);time.setText("Uhrzeit: "+String.format(Locale.GERMANY,"%02d:%02d",hh,mi)+"  ✎");},cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true).show());
        receiptAddButton=text("＋  Quittung hinzufügen",14,GREEN,true);receiptAddButton.setPadding(0,dp(10),0,dp(10));box.addView(receiptAddButton);receiptAddButton.setOnClickListener(v->receiptChoiceDialog());
        if(existing!=null){Cursor e=db.one(existing[0]);if(e.moveToFirst()){purpose.setText(e.getString(1));amount.setText(""+e.getDouble(3));activeReceiptPath=e.getString(5)==null?"":e.getString(5);if(!activeReceiptPath.isEmpty()&&new File(activeReceiptPath).exists())receiptAddButton.setText("🧾  Quittung ändern / hinzufügen");try{cal.setTime(new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).parse(e.getString(2)));}catch(Exception ignored){}String savedTime=e.getString(6)==null?"00:00":e.getString(6);try{String[] hm=savedTime.split(":");cal.set(Calendar.HOUR_OF_DAY,Integer.parseInt(hm[0]));cal.set(Calendar.MINUTE,Integer.parseInt(hm[1]));}catch(Exception ignored){}date.setText("Datum: "+e.getString(2)+"  ✎");time.setText("Uhrzeit: "+savedTime+"  ✎");}e.close();}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(income?"Einnahme erfassen":"Ausgabe erfassen").setView(scrollableForm(box)).setNegativeButton("Abbrechen",null).setPositiveButton(existing==null?"Speichern":"Änderungen speichern",(d,w)->{
            String p=purpose.getText().toString().trim();String a=amount.getText().toString().trim().replace(",",".");if(a.isEmpty()){toast("Bitte einen Betrag eingeben.");return;}
            try{double n=Double.parseDouble(a);if(n<=0)throw new Exception();String dt=new SimpleDateFormat("dd.MM.yyyy",Locale.GERMANY).format(cal.getTime());String tm=new SimpleDateFormat("HH:mm",Locale.GERMANY).format(cal.getTime());if(existing==null)db.addEntry(accountId,p,dt,tm,n,income,activeReceiptPath);else db.updateEntry(existing[0],p,dt,tm,n,income,activeReceiptPath);refresh();scheduleAutomaticBackup();}catch(Exception ex){toast("Bitte einen gültigen Betrag eingeben.");}
        }).create();dialog.show();enableDialogKeyboardResize(dialog);
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
    void exportDialog(){new AlertDialog.Builder(this).setTitle("Exportformat wählen").setItems(new String[]{"PDF (A4)","Excel (CSV)"},(d,w)->{exportRows.clear();if(selectedMonth>0){double transfer=db.balanceBeforeMonth(accountId,selectedYear,selectedMonth);Calendar previous=Calendar.getInstance();previous.set(selectedYear,selectedMonth-2,1);String label=months[previous.get(Calendar.MONTH)];if(selectedMonth==1)label+=" "+previous.get(Calendar.YEAR);exportRows.add(new String[]{"Übertrag Saldo ("+label+")","",String.format(Locale.US,"%.2f",transfer),"Übertrag"});}Cursor c=db.entries(accountId,selectedMonth,selectedYear,0);while(c.moveToNext())exportRows.add(new String[]{(c.getString(1)==null||c.getString(1).trim().isEmpty())?"Ohne Zweck":c.getString(1),c.getString(2)+" "+(c.getString(6)==null?"00:00":c.getString(6)),String.format(Locale.US,"%.2f",c.getDouble(3)),c.getInt(4)==1?"Einnahme":"Ausgabe"});c.close();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType(w==0?"application/pdf":"text/csv");i.putExtra(Intent.EXTRA_TITLE,w==0?"Meine-Finanzen.pdf":"Meine-Finanzen.csv");startActivityForResult(i,w==0?REQ_PDF:REQ_CSV);}).show();}
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
            int amountColor=r[3].equals("Einnahme")?GREEN:(r[3].equals("Übertrag")?BLUE:RED);
            p.setColor(r[3].equals("Einnahme")?0xFFE3F3E8:(r[3].equals("Übertrag")?0xFFEAF1FA:0xFFFBE5E7));
            c.drawRoundRect(432,y-16,558,y+5,7,7,p);
            p.setColor(amountColor);p.setFakeBoldText(true);c.drawText(r[2],440,y,p);p.setFakeBoldText(false);y+=24;
        }
        double currentBalance=db.accountBalance(accountId);
        p.setColor(0xFFEAF1FA);c.drawRoundRect(330,790,558,824,8,8,p);
        p.setColor(BLUE);p.setTextSize(13);p.setFakeBoldText(true);String saldoText="Aktueller Saldo: "+money(currentBalance);float saldoWidth=p.measureText(saldoText);c.drawText(saldoText,550-saldoWidth,812,p);p.setFakeBoldText(false);
        pdf.finishPage(page);try(OutputStream o=getContentResolver().openOutputStream(uri)){pdf.writeTo(o);}pdf.close();toast("PDF im A4-Format gespeichert.");
    }
    void menu(){
        LinearLayout panel=column();panel.setPadding(dp(16),dp(12),dp(16),dp(12));panel.setBackground(shape(BG,24));
        TextView heading=text("Meine Finanzen",20,DARK,true);heading.setPadding(dp(6),dp(4),dp(6),dp(12));panel.addView(heading);
        String[] titles={"Konten verwalten","Meine Fixkosten","Sicherheit","Datensicherung"};
        String[] subtitles={"Konten hinzufügen, umbenennen oder löschen","Fixkosten für das ausgewählte Konto","PIN und Fingerabdruck verwalten","Backup speichern oder wiederherstellen"};
        String[] icons={"▣","↻","⌑","☁"};int[] colors={0xFFE4F2E9,0xFFEAF1FA,0xFFFFF2DF,0xFFEDE8F8};
        AlertDialog dialog=new AlertDialog.Builder(this).setView(panel).create();
        for(int i=0;i<titles.length;i++){final int action=i;LinearLayout item=row();item.setBackground(shape(0xFFFFFFFF,18));item.setPadding(dp(12),dp(11),dp(12),dp(11));LinearLayout.LayoutParams itemParams=lp(-1,-2);itemParams.bottomMargin=dp(9);panel.addView(item,itemParams);
            TextView symbol=text(icons[i],22,DARK,true);symbol.setGravity(Gravity.CENTER);symbol.setBackground(shape(colors[i],30));item.addView(symbol,lp(44,44));
            LinearLayout details=column();LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(0,-2,1);detailParams.leftMargin=dp(12);item.addView(details,detailParams);details.addView(text(titles[i],15,DARK,true));details.addView(text(subtitles[i],12,0xFF718078,false));item.addView(text("›",25,0xFF718078,false));
            item.setOnClickListener(v->{dialog.dismiss();if(action==0)accountsDialog();else if(action==1)fixedCostsDialog();else if(action==2)securityDialog();else backupDialog();});
        }
        Button close=button("Schliessen",0xFF68756E);panel.addView(close,lp(-1,46));close.setOnClickListener(v->dialog.dismiss());dialog.show();
    }

    interface MenuAction{void selected(int index);}
    void roundedOptionsDialog(String title,String[] titles,String[] subtitles,String[] icons,int[] colors,MenuAction action){roundedOptionsDialog(title,titles,subtitles,icons,colors,-1,action);}
    void roundedOptionsDialog(String title,String[] titles,String[] subtitles,String[] icons,int[] colors,int checkedIndex,MenuAction action){
        LinearLayout panel=column();panel.setPadding(dp(16),dp(14),dp(16),dp(12));panel.setBackground(shape(BG,24));
        TextView heading=text(title,20,DARK,true);heading.setPadding(dp(6),dp(2),dp(6),dp(14));panel.addView(heading);
        AlertDialog dialog=new AlertDialog.Builder(this).setView(panel).create();
        for(int i=0;i<titles.length;i++){
            final int selected=i;LinearLayout item=row();item.setBackground(shape(0xFFFFFFFF,18));item.setPadding(dp(12),dp(11),dp(12),dp(11));
            LinearLayout.LayoutParams itemParams=lp(-1,-2);itemParams.bottomMargin=dp(9);panel.addView(item,itemParams);
            TextView symbol=text(icons[i],21,DARK,true);symbol.setGravity(Gravity.CENTER);symbol.setBackground(shape(colors[i],30));item.addView(symbol,lp(44,44));
            LinearLayout details=column();LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(0,-2,1);detailParams.leftMargin=dp(12);item.addView(details,detailParams);
            details.addView(text(titles[i],15,DARK,true));details.addView(text(subtitles[i],12,0xFF718078,false));
            item.addView(text(selected==checkedIndex?"✓":"›",selected==checkedIndex?21:25,selected==checkedIndex?GREEN:0xFF718078,true));item.setOnClickListener(v->{dialog.dismiss();action.selected(selected);});
        }
        Button close=button("Schliessen",0xFF68756E);panel.addView(close,lp(-1,46));close.setOnClickListener(v->dialog.dismiss());
        dialog.show();
    }

    void fixedCostsDialog(){
        LinearLayout content=column();content.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView accountLabel=text("Fixkosten für: "+db.accountName(accountId),14,GREEN,true);accountLabel.setPadding(dp(4),dp(4),dp(4),dp(10));content.addView(accountLabel);
        LinearLayout items=column();ScrollView listScroll=new ScrollView(this);listScroll.setFillViewport(false);listScroll.addView(items);content.addView(listScroll,lp(-1,220));
        EditText purpose=new EditText(this),amount=new EditText(this);purpose.setHint("Bezeichnung");amount.setHint("Betrag in CHF");amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner type=new Spinner(this);type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Ausgabe","Einnahme"}));
        content.addView(text("Neue Fixkosten erfassen",14,DARK,true));content.addView(purpose,lp(-1,48));content.addView(amount,lp(-1,48));content.addView(type,lp(-1,48));
        LinearLayout buttons=row();content.addView(buttons,lp(-1,52));Button add=button("Hinzufügen",GREEN),close=button("Schliessen",0xFF68756E);
        LinearLayout.LayoutParams bp=lp(0,44);bp.weight=1;bp.rightMargin=dp(5);buttons.addView(add,bp);LinearLayout.LayoutParams cp=lp(0,44);cp.weight=1;cp.leftMargin=dp(5);buttons.addView(close,cp);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Meine Fixkosten").setView(scrollableForm(content)).create();
        Runnable reload=()->{items.removeAllViews();Cursor c=db.fixedCosts(accountId);boolean any=false;
            while(c.moveToNext()){any=true;int id=c.getInt(0);String name=c.getString(1);double value=c.getDouble(2);boolean income=c.getInt(3)==1;
                LinearLayout item=column();item.setPadding(dp(10),dp(8),dp(10),dp(8));item.setBackground(shape(0xFFFFFFFF,10));
                item.addView(text(name,16,DARK,true));item.addView(text(money(value)+" · "+(income?"Einnahme":"Ausgabe"),13,income?GREEN:RED,false));
                LinearLayout.LayoutParams rowp=lp(-1,-2);rowp.bottomMargin=dp(6);items.addView(item,rowp);item.setOnClickListener(v->editFixedCost(id));
            }c.close();if(!any)items.addView(text("Noch keine Fixkosten gespeichert.",14,0xFF77847D,false));
        };reload.run();
        add.setOnClickListener(v->{String p=purpose.getText().toString().trim(),raw=amount.getText().toString().trim().replace(",",".");
            if(p.isEmpty()||raw.isEmpty()){toast("Bitte Bezeichnung und Betrag eingeben.");return;}
            try{double value=Double.parseDouble(raw);if(value<=0)throw new NumberFormatException();db.addFixedCost(accountId,p,value,type.getSelectedItemPosition()==1);scheduleAutomaticBackup();purpose.setText("");amount.setText("");type.setSelection(0);reload.run();toast("Fixkosten gespeichert.");}
            catch(Exception ex){toast("Bitte einen gültigen Betrag eingeben.");}
        });
        close.setOnClickListener(v->dialog.dismiss());dialog.show();enableDialogKeyboardResize(dialog);
    }
    void editFixedCost(int id){
        EditText purpose=new EditText(this),amount=new EditText(this);purpose.setHint("Bezeichnung");amount.setHint("Betrag in CHF");amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner type=new Spinner(this);type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Ausgabe","Einnahme"}));
        LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),dp(8));box.addView(purpose);box.addView(amount);box.addView(type);
        try(Cursor c=db.fixedCost(id)){while(c.moveToNext())if(c.getInt(0)==id){purpose.setText(c.getString(1));amount.setText(String.valueOf(c.getDouble(2)));type.setSelection(c.getInt(3)==1?1:0);break;}}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Fixkosten bearbeiten").setView(scrollableForm(box)).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",(d,w)->{
            String p=purpose.getText().toString().trim(),raw=amount.getText().toString().trim().replace(",",".");try{double value=Double.parseDouble(raw);if(p.isEmpty()||value<=0)throw new NumberFormatException();db.updateFixedCost(id,p,value,type.getSelectedItemPosition()==1);scheduleAutomaticBackup();toast("Fixkosten gespeichert.");}catch(Exception ex){toast("Bitte Bezeichnung und gültigen Betrag eingeben.");}
        }).setNeutralButton("Löschen",(d,w)->new AlertDialog.Builder(this).setMessage("Diese Fixkosten löschen?").setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(a,b)->{db.deleteFixedCost(id);scheduleAutomaticBackup();toast("Fixkosten gelöscht.");}).show()).create();dialog.show();enableDialogKeyboardResize(dialog);
    }
    void insertFixedCosts(){
        if(accountId<0){toast("Bitte zuerst ein Konto auswählen.");return;}
        try(Cursor c=db.fixedCosts(accountId)){if(!c.moveToFirst()){toast("Für dieses Konto sind noch keine Fixkosten gespeichert.");return;}}
        Calendar cal=Calendar.getInstance();
        new DatePickerDialog(this,(view,yy,mm,dd)->{
            String date=String.format(Locale.GERMANY,"%02d.%02d.%04d",dd,mm+1,yy);int count=0;
            ArrayList<String[]> fixed=new ArrayList<>();
            try(Cursor c=db.fixedCosts(accountId)){
                while(c.moveToNext())fixed.add(new String[]{c.getString(1),String.valueOf(c.getDouble(2)),String.valueOf(c.getInt(3))});
            }
            // Einträge werden in der Liste nach ID absteigend angezeigt.
            // Deshalb zuerst Ausgaben, danach Einnahmen einfügen: Einnahmen stehen oben.
            // Rückwärts einfügen erhält innerhalb beider Gruppen die bisherige Fixkosten-Reihenfolge.
            for(int i=fixed.size()-1;i>=0;i--){
                String[] item=fixed.get(i);
                if("0".equals(item[2])){db.addEntry(accountId,item[0],date,"00:00",Double.parseDouble(item[1]),false,"");count++;}
            }
            for(int i=fixed.size()-1;i>=0;i--){
                String[] item=fixed.get(i);
                if("1".equals(item[2])){db.addEntry(accountId,item[0],date,"00:00",Double.parseDouble(item[1]),true,"");count++;}
            }
            refresh();scheduleAutomaticBackup();toast(count+" Fixkosten für den "+date+" eingefügt.");
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).show();
    }
    void accountsDialog(){
        roundedOptionsDialog("Konten verwalten",
            new String[]{"Konto hinzufügen","Konto umbenennen","Konto löschen"},
            new String[]{"Ein weiteres Konto erstellen","Name des ausgewählten Kontos ändern","Konto samt Einträgen und Fixkosten entfernen"},
            new String[]{"+","✎","−"},new int[]{0xFFE4F2E9,0xFFEAF1FA,0xFFFFE8E8},w->{
                if(w==0)askText("Neues Konto",name->{db.addAccount(name);getPreferences(0).edit().putString("last_account_name",name).apply();accountId=-1;reloadAccounts();refresh();});
                else if(w==1)askText("Neuer Kontoname",name->{db.renameAccount(accountId,name);getPreferences(0).edit().putString("last_account_name",name).apply();reloadAccounts();refresh();});
                else new AlertDialog.Builder(this).setMessage("Konto und alle zugehörigen Einträge sowie Fixkosten löschen?").setNegativeButton("Abbrechen",null).setPositiveButton("Löschen",(a,b)->{db.deleteAccount(accountId);ensureAccount();accountId=-1;reloadAccounts();refresh();scheduleAutomaticBackup();}).show();
            });
    }
    interface TextDone{void done(String s);}
    void askText(String title,TextDone done){
        EditText e=new EditText(this);e.setHint("Name");LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),dp(8));box.addView(e);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(scrollableForm(box)).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",(d,w)->{String value=e.getText().toString().trim();if(!value.isEmpty())done.done(value);}).create();
        dialog.show();enableDialogKeyboardResize(dialog);
    }
    void securityDialog(){
        boolean pinOn=getPreferences(0).getBoolean("pin_on",false),bioOn=getPreferences(0).getBoolean("bio_on",false);
        roundedOptionsDialog("Sicherheit",
            new String[]{"PIN-Abfrage "+(pinOn?"deaktivieren":"aktivieren"),"PIN ändern","Fingerabdruck "+(bioOn?"deaktivieren":"aktivieren")},
            new String[]{pinOn?"Die PIN beim Öffnen der App nicht mehr abfragen":"App mit einer PIN schützen","Eine neue PIN festlegen","Biometrische Anmeldung verwalten"},
            new String[]{"●","✎","◎"},new int[]{0xFFEAF1FA,0xFFE4F2E9,0xFFEDE8F8},w->{
                if(w==0){if(pinOn){getPreferences(0).edit().putBoolean("pin_on",false).apply();toast("PIN-Abfrage deaktiviert.");}else setNewPin();}
                else if(w==1)setNewPin();else toggleBiometric();
            });
    }
    void setNewPin(){
        EditText e=new EditText(this);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);e.setHint("Neue PIN (mind. 4 Ziffern)");
        LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),dp(8));box.addView(e);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("PIN festlegen").setView(scrollableForm(box)).setNegativeButton("Abbrechen",null).setPositiveButton("Speichern",(d,w)->{
            String p=e.getText().toString();if(p.length()<4){toast("PIN muss mindestens 4 Ziffern haben.");return;}
            getPreferences(0).edit().putString("pin",p).putBoolean("pin_on",true).apply();toast("PIN aktiviert.");
        }).create();dialog.show();enableDialogKeyboardResize(dialog);
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
        LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),dp(8));box.addView(e);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("PIN eingeben").setMessage("Bitte PIN zum Öffnen eingeben.").setView(scrollableForm(box)).setCancelable(false)
            .setPositiveButton("Entsperren",(d,w)->{if(!e.getText().toString().equals(getPreferences(0).getString("pin",""))){toast("PIN falsch. Bitte App erneut öffnen und nochmals versuchen.");finish();}})
            .setNegativeButton("Beenden",(d,w)->finish()).create();dialog.show();enableDialogKeyboardResize(dialog);
    }
    void backupDialog(){
        boolean connected=getPreferences(0).getString("google_drive_backup_uri",null)!=null;
        roundedOptionsDialog("Datensicherung",
            new String[]{"Backup auf Gerät speichern","Backup importieren / wiederherstellen","Google Drive-Backup "+(connected?"ändern":"einrichten"),"Google Drive-Backup wiederherstellen","Google Drive-Backup deaktivieren"},
            new String[]{"Sicherung als Datei exportieren","Vorhandene Sicherungsdatei einlesen","Automatische Sicherung in Google Drive "+(connected?"verwalten":"verbinden"),"Daten aus Google Drive übernehmen","Verknüpfung zu Google Drive entfernen"},
            new String[]{"↓","↑","☁","↻","×"},new int[]{0xFFEAF1FA,0xFFE4F2E9,0xFFEDE8F8,0xFFFFF2DF,0xFFFFE8E8},w->{
                if(w==0){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"Meine-Finanzen-Backup.json");startActivityForResult(i,REQ_BACKUP);}
                else if(w==1){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");startActivityForResult(i,REQ_RESTORE);}
                else if(w==2)chooseGoogleDriveBackupFile();
                else if(w==3){
                    String saved=getPreferences(0).getString("google_drive_backup_uri",null);
                    if(saved!=null){try{readBackup(Uri.parse(saved));}catch(Exception e){showErrorDialog("Google-Drive-Backup konnte nicht gelesen werden",e.toString());}}
                    else chooseGoogleDriveBackupForRestore();
                }else{
                    String saved=getPreferences(0).getString("google_drive_backup_uri",null);
                    if(saved!=null){try{getContentResolver().releasePersistableUriPermission(Uri.parse(saved),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}}
                    getPreferences(0).edit().remove("google_drive_backup_uri").remove("google_drive_backup_fingerprint").apply();
                    toast("Google-Drive-Backup-Verbindung entfernt. Die lokale automatische Sicherung bleibt aktiv.");
                }
            });
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
    void scheduleAutomaticBackup(){
        try{cloudExecutor.execute(()->saveAutomaticBackup());}
        catch(Exception e){android.util.Log.e("MeineFinanzen","Automatische Sicherung konnte nicht eingeplant werden",e);}
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
        }catch(Exception e){android.util.Log.e("MeineFinanzen","Automatisches Backup fehlgeschlagen",e);runOnUiThread(()->toast("Automatisches Backup fehlgeschlagen. Bitte Google-Drive-Verbindung prüfen."));}
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
        org.json.JSONArray aa=new org.json.JSONArray(),ee=new org.json.JSONArray(),ff=new org.json.JSONArray();
        for(String a:db.accounts())aa.put(a);
        Cursor c=db.allEntries();
        while(c.moveToNext()){
            org.json.JSONObject o=new org.json.JSONObject();o.put("account",c.getString(1));o.put("purpose",c.getString(2));o.put("date",c.getString(3));o.put("time",c.getString(7)==null?"00:00":c.getString(7));o.put("amount",c.getDouble(4));o.put("income",c.getInt(5)==1);String receiptPath=c.getString(6);if(receiptPath!=null&&!receiptPath.isEmpty()&&new File(receiptPath).exists()){File receiptFile=new File(receiptPath);o.put("receipt_file",android.util.Base64.encodeToString(readFileBytes(receiptPath),android.util.Base64.NO_WRAP));o.put("receipt_name",receiptFile.getName());}ee.put(o);
        }
        c.close();Cursor fc=db.allFixedCosts();while(fc.moveToNext()){org.json.JSONObject o=new org.json.JSONObject();o.put("account",db.accountName(fc.getInt(4)));o.put("purpose",fc.getString(1));o.put("amount",fc.getDouble(2));o.put("income",fc.getInt(3)==1);ff.put(o);}fc.close();org.json.JSONObject root=new org.json.JSONObject();root.put("accounts",aa);root.put("entries",ee);root.put("fixed_costs",ff);root.put("backup_version",2);root.put("created_at",System.currentTimeMillis());return root.toString(2);
    }
    void writeBackup(Uri uri)throws Exception{
        try(Writer w=new OutputStreamWriter(getContentResolver().openOutputStream(uri),"UTF-8")){w.write(backupJson());}
        toast("Backup gespeichert.");
    }
    void readBackup(Uri uri)throws Exception{StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri),"UTF-8"))){String line;while((line=r.readLine())!=null)s.append(line);}restoreBackupJson(s.toString());}
    void restoreBackupJson(String json)throws Exception{org.json.JSONObject j=new org.json.JSONObject(json);org.json.JSONArray aa=j.getJSONArray("accounts"),ee=j.getJSONArray("entries"),ff=j.optJSONArray("fixed_costs");db.clearAll();db.clearFixedCosts();for(int i=0;i<aa.length();i++)db.addAccount(aa.getString(i));for(int i=0;i<ee.length();i++){org.json.JSONObject o=ee.getJSONObject(i);int aid=db.accountId(o.getString("account"));if(aid>0){String receiptPath="";String fileData=o.optString("receipt_file","");if(!fileData.isEmpty())receiptPath=restoreReceiptFile(fileData,o.optString("receipt_name","Quittung"));else{String image=o.optString("receipt_image","");if(!image.isEmpty())receiptPath=restoreReceiptImage(image);}db.addEntry(aid,o.optString("purpose",""),o.getString("date"),o.optString("time","00:00"),o.getDouble("amount"),o.getBoolean("income"),receiptPath);}}if(ff!=null)for(int i=0;i<ff.length();i++){org.json.JSONObject o=ff.getJSONObject(i);String account=o.optString("account",aa.length()>0?aa.getString(0):"");int fixedAccountId=db.accountId(account);if(fixedAccountId>0)db.addFixedCost(fixedAccountId,o.optString("purpose",""),o.getDouble("amount"),o.optBoolean("income",false));}ensureAccount();accountId=-1;reloadAccounts();refresh();scheduleAutomaticBackup();toast("Backup wiederhergestellt.");}
    void showErrorDialog(String title,String detail){TextView message=text(detail,14,DARK,false);message.setTextIsSelectable(true);message.setPadding(dp(16),dp(12),dp(16),dp(12));ScrollView scroll=new ScrollView(this);scroll.addView(message);new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNeutralButton("Fehler kopieren",(d,w)->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Fehlermeldung",detail));toast("Fehlermeldung kopiert.");}).setPositiveButton("Schliessen",null).show();}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    static class DB extends SQLiteOpenHelper{
        DB(Context c){super(c,"finanzen.db",null,5);}
        public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE accounts(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT UNIQUE)");d.execSQL("CREATE TABLE entries(id INTEGER PRIMARY KEY AUTOINCREMENT,account_id INTEGER,purpose TEXT,date TEXT,amount REAL,income INTEGER,receipt_path TEXT,entry_time TEXT DEFAULT '00:00')");d.execSQL("CREATE TABLE fixed_costs(id INTEGER PRIMARY KEY AUTOINCREMENT,purpose TEXT,amount REAL,income INTEGER,account_id INTEGER)");}
        public void onUpgrade(SQLiteDatabase d,int a,int b){if(a<2)d.execSQL("ALTER TABLE entries ADD COLUMN receipt_path TEXT");if(a<3)d.execSQL("ALTER TABLE entries ADD COLUMN entry_time TEXT DEFAULT '00:00'");if(a<4)d.execSQL("CREATE TABLE fixed_costs(id INTEGER PRIMARY KEY AUTOINCREMENT,purpose TEXT,amount REAL,income INTEGER)");if(a<5){d.execSQL("ALTER TABLE fixed_costs ADD COLUMN account_id INTEGER");d.execSQL("UPDATE fixed_costs SET account_id=(SELECT id FROM accounts ORDER BY id LIMIT 1) WHERE account_id IS NULL");}}
        void addAccount(String n){getWritableDatabase().execSQL("INSERT OR IGNORE INTO accounts(name) VALUES(?)",new Object[]{n});}
        void renameAccount(int id,String n){getWritableDatabase().execSQL("UPDATE accounts SET name=? WHERE id=?",new Object[]{n,id});}
        void deleteAccount(int id){getWritableDatabase().execSQL("DELETE FROM entries WHERE account_id=?",new Object[]{id});getWritableDatabase().execSQL("DELETE FROM fixed_costs WHERE account_id=?",new Object[]{id});getWritableDatabase().execSQL("DELETE FROM accounts WHERE id=?",new Object[]{id});}
        void clearAll(){getWritableDatabase().execSQL("DELETE FROM entries");getWritableDatabase().execSQL("DELETE FROM accounts");}
        void clearFixedCosts(){getWritableDatabase().execSQL("DELETE FROM fixed_costs");}
        void addFixedCost(int accountId,String purpose,double amount,boolean income){getWritableDatabase().execSQL("INSERT INTO fixed_costs(purpose,amount,income,account_id) VALUES(?,?,?,?)",new Object[]{purpose,amount,income?1:0,accountId});}
        void updateFixedCost(int id,String purpose,double amount,boolean income){getWritableDatabase().execSQL("UPDATE fixed_costs SET purpose=?,amount=?,income=? WHERE id=?",new Object[]{purpose,amount,income?1:0,id});}
        void deleteFixedCost(int id){getWritableDatabase().execSQL("DELETE FROM fixed_costs WHERE id=?",new Object[]{id});}
        Cursor fixedCosts(int accountId){return getReadableDatabase().rawQuery("SELECT id,purpose,amount,income FROM fixed_costs WHERE account_id=? ORDER BY id",new String[]{String.valueOf(accountId)});} Cursor fixedCost(int id){return getReadableDatabase().rawQuery("SELECT id,purpose,amount,income FROM fixed_costs WHERE id=?",new String[]{String.valueOf(id)});} Cursor allFixedCosts(){return getReadableDatabase().rawQuery("SELECT id,purpose,amount,income,account_id FROM fixed_costs ORDER BY account_id,id",null);}
        ArrayList<String> accounts(){ArrayList<String>a=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery("SELECT name FROM accounts ORDER BY id",null)){while(c.moveToNext())a.add(c.getString(0));}return a;}
        int accountId(String n){try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM accounts WHERE name=?",new String[]{n})){return c.moveToFirst()?c.getInt(0):-1;}}
        String accountName(int id){try(Cursor c=getReadableDatabase().rawQuery("SELECT name FROM accounts WHERE id=?",new String[]{""+id})){return c.moveToFirst()?c.getString(0):"";}}
        int countAccounts(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM accounts",null)){return c.moveToFirst()?c.getInt(0):0;}}
        double accountBalance(int id){try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(CASE WHEN income=1 THEN amount ELSE -amount END),0) FROM entries WHERE account_id=?",new String[]{""+id})){return c.moveToFirst()?c.getDouble(0):0;}}
        double balanceBeforeMonth(int id,int year,int month){
            Calendar first=Calendar.getInstance();first.clear();first.set(year,month-1,1);
            String cutoff=String.format(Locale.US,"%04d%02d%02d",year,month,1);
            String sql="SELECT COALESCE(SUM(CASE WHEN income=1 THEN amount ELSE -amount END),0) FROM entries WHERE account_id=? AND (substr(date,7,4)||substr(date,4,2)||substr(date,1,2))<?";
            try(Cursor c=getReadableDatabase().rawQuery(sql,new String[]{String.valueOf(id),cutoff})){return c.moveToFirst()?c.getDouble(0):0;}
        }
        void addEntry(int id,String p,String date,String time,double amount,boolean income,String receipt){getWritableDatabase().execSQL("INSERT INTO entries(account_id,purpose,date,amount,income,receipt_path,entry_time) VALUES(?,?,?,?,?,?,?)",new Object[]{id,p,date,amount,income?1:0,receipt,time});}
        void updateEntry(int id,String p,String date,String time,double amount,boolean income,String receipt){getWritableDatabase().execSQL("UPDATE entries SET purpose=?,date=?,amount=?,income=?,receipt_path=?,entry_time=? WHERE id=?",new Object[]{p,date,amount,income?1:0,receipt,time,id});}
        void deleteEntry(int id){getWritableDatabase().execSQL("DELETE FROM entries WHERE id=?",new Object[]{id});}
        Cursor one(int id){return getReadableDatabase().rawQuery("SELECT id,purpose,date,amount,income,receipt_path,entry_time FROM entries WHERE id=?",new String[]{""+id});}
        Cursor entries(int aid,int month,int year,int sortOrder){String direction=sortOrder==1?"ASC":"DESC";String sql="SELECT id,purpose,date,amount,income,receipt_path,entry_time FROM entries WHERE account_id=? AND substr(date,7,4)=?"+(month==0?"":" AND substr(date,4,2)=?")+" ORDER BY substr(date,7,4) "+direction+",substr(date,4,2) "+direction+",substr(date,1,2) "+direction+",entry_time "+direction+",id "+direction;return month==0?getReadableDatabase().rawQuery(sql,new String[]{""+aid,""+year}):getReadableDatabase().rawQuery(sql,new String[]{""+aid,""+year,String.format(Locale.US,"%02d",month)});}
        Cursor allEntries(){return getReadableDatabase().rawQuery("SELECT e.id,a.name,e.purpose,e.date,e.amount,e.income,e.receipt_path,e.entry_time FROM entries e JOIN accounts a ON a.id=e.account_id",null);}
    }
}
