package com.goalsreminder.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends android.app.Activity {
    private static final int BG=Color.rgb(7,10,18), SURFACE=Color.rgb(21,27,43),
            SURFACE2=Color.rgb(30,37,57), TEXT=Color.rgb(242,245,252),
            MUTED=Color.rgb(151,162,183), ACCENT=Color.rgb(61,155,255),
            PURPLE=Color.rgb(132,82,255), GOOD=Color.rgb(97,210,155);
    private DbHelper db;
    private FrameLayout root;
    private FrameLayout host;
    private LinearLayout drawer;
    private View scrim;
    private TextView hamburger;
    private boolean drawerOpen=false;
    private int screen=0;
    private static final int REQ_EXPORT_BACKUP=501;
    private static final int REQ_IMPORT_BACKUP=502;
    private String pendingBackupType="full";
    private LocalDate pendingBackupFrom=null;
    private LocalDate pendingBackupTo=null;
    private LocalDate rangeFrom=LocalDate.now().withDayOfMonth(1);
    private LocalDate rangeTo=LocalDate.now();
    private String language="fa";
    private final Handler clockHandler=new Handler(Looper.getMainLooper());
    private final List<TaskUi> taskUis=new ArrayList<>();
    private final Runnable taskTicker=new Runnable(){
        @Override public void run(){
            if(screen!=0)return;
            boolean reorder=false;
            LocalDateTime now=LocalDateTime.now();
            for(TaskUi ui:new ArrayList<>(taskUis)){
                int before=ui.state;
                updateTaskUi(ui,now);
                if(before!=-1 && before!=ui.state)reorder=true;
            }
            if(reorder){
                showToday();
                return;
            }
            clockHandler.postDelayed(this,5000);
        }
    };

    private static class TaskUi{
        DayTask task;
        LinearLayout row;
        ProgressBar bar;
        TextView status;
        int state=-1;
    }

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        language=getSharedPreferences("goals_settings",MODE_PRIVATE).getString("language","fa");
        db=new DbHelper(this); db.ensureDay(LocalDate.now());
        AlarmScheduler.scheduleAll(this);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},410);
        buildShell(); showToday();
    }

    @Override protected void onResume(){
        super.onResume();
        if(db!=null){db.ensureDay(LocalDate.now()); AlarmScheduler.scheduleAll(this); render();}
    }

    @Override protected void onPause(){
        super.onPause();
        clockHandler.removeCallbacks(taskTicker);
    }

    private void buildShell(){
        root=new FrameLayout(this);
        root.setBackground(appBackground());
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        host=new FrameLayout(this);
        host.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.addView(host,new FrameLayout.LayoutParams(-1,-1));

        scrim=new View(this);
        scrim.setBackgroundColor(Color.argb(150,0,0,0));
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v->closeDrawer());
        root.addView(scrim,new FrameLayout.LayoutParams(-1,-1));

        drawer=new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        drawer.setPadding(dp(18),dp(16),dp(18),dp(22));
        drawer.setBackground(glassDrawable(240,22));
        drawer.setElevation(dp(22));
        drawer.setVisibility(View.GONE);
        FrameLayout.LayoutParams dpv=new FrameLayout.LayoutParams(dp(292),-1,Gravity.LEFT);
        dpv.setMargins(dp(8),dp(8),0,dp(8));
        root.addView(drawer,dpv);

        hamburger=new TextView(this);
        hamburger.setText("☰");
        hamburger.setTextColor(TEXT);
        hamburger.setTextSize(25);
        hamburger.setGravity(Gravity.CENTER);
        hamburger.setBackground(glassDrawable(225,18));
        hamburger.setElevation(dp(14));
        hamburger.setOnClickListener(v->openDrawer());
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(dp(54),dp(54),Gravity.LEFT|Gravity.TOP);
        hp.setMargins(dp(14),dp(12),0,0);
        root.addView(hamburger,hp);

        buildDrawer();
        setContentView(root);
    }

    private void buildDrawer(){
        drawer.removeAllViews();

        LinearLayout top=new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo=new ImageView(this);
        logo.setImageBitmap(LogoAsset.bitmap());
        logo.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        top.addView(logo,new LinearLayout.LayoutParams(dp(58),dp(44)));

        View fill=new View(this);
        top.addView(fill,new LinearLayout.LayoutParams(0,1,1));

        TextView close=new TextView(this);
        close.setText("×");
        close.setTextColor(TEXT);
        close.setTextSize(30);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v->closeDrawer());
        top.addView(close,new LinearLayout.LayoutParams(dp(46),dp(46)));
        drawer.addView(top);

        TextView brand=new TextView(this);
        brand.setText("Goals Reminder");
        brand.setTextColor(TEXT);
        brand.setTextSize(24);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setPadding(0,dp(12),0,dp(4));
        drawer.addView(brand);

        TextView tagline=new TextView(this);
        tagline.setText("Plan  •  Track  •  Reflect  •  Grow");
        tagline.setTextColor(MUTED);
        tagline.setTextSize(12);
        tagline.setPadding(0,0,0,dp(22));
        drawer.addView(tagline);

        addMenuItem("Today",R.drawable.ic_today,0,()->showToday());
        addMenuItem("History",R.drawable.ic_history,1,()->showHistory());
        addMenuItem("Analytics",R.drawable.ic_analytics,2,()->showAnalysisWeek());
        addMenuItem("Backup & Restore",R.drawable.ic_backup,3,()->showBackupRestore());
        addMenuItem("Settings",R.drawable.ic_settings,4,()->showSettings());

        View spacer=new View(this);
        drawer.addView(spacer,new LinearLayout.LayoutParams(1,0,1));

        TextView footer=new TextView(this);
        footer.setText("Goals Reminder\nOffline • Local-first");
        footer.setTextColor(Color.rgb(111,124,151));
        footer.setTextSize(11);
        footer.setGravity(Gravity.LEFT);
        drawer.addView(footer);
    }

    private void addMenuItem(String label,int iconRes,int target,Runnable action){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14),0,dp(12),0);
        row.setBackground(menuItemDrawable(screen==target));
        row.setClickable(true);

        ImageView icon=new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(screen==target?Color.WHITE:Color.rgb(185,196,218));
        row.addView(icon,new LinearLayout.LayoutParams(dp(28),dp(28)));

        TextView name=new TextView(this);
        name.setText(label);
        name.setTextColor(TEXT);
        name.setTextSize(15);
        name.setTypeface(screen==target?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
        name.setPadding(dp(16),0,0,0);
        row.addView(name,new LinearLayout.LayoutParams(0,-2,1));

        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(58));
        rp.setMargins(0,dp(5),0,dp(5));
        drawer.addView(row,rp);

        row.setOnClickListener(v->{ action.run(); closeDrawer(); });
    }

    private void openDrawer(){
        if(drawerOpen)return;
        drawerOpen=true;
        buildDrawer();
        scrim.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-dp(320));
        hamburger.setVisibility(View.INVISIBLE);
        drawer.animate().translationX(0).setDuration(220).start();
    }

    private void closeDrawer(){
        if(!drawerOpen)return;
        drawerOpen=false;
        drawer.animate().translationX(-dp(320)).setDuration(180).withEndAction(()->{
            drawer.setVisibility(View.GONE);
            scrim.setVisibility(View.GONE);
            hamburger.setVisibility(View.VISIBLE);
        }).start();
    }

    private void render(){
        if(host==null)return;
        if(screen==0)showToday();
        else if(screen==1)showHistory();
        else if(screen==2)showAnalysisWeek();
        else if(screen==3)showBackupRestore();
        else showSettings();
    }

    private void showToday(){
        screen=0; host.removeAllViews(); ScrollView sv=new ScrollView(this); LinearLayout p=page();
        LocalDate today=LocalDate.now(); db.ensureDay(today);
        p.addView(title("امروز")); TextView d=small(PersianDate.formatWithWeekday(today)); d.setTextColor(ACCENT); p.addView(d);

        if(!canExact()){
            TextView w=small("برای نوتیفیکیشن دقیق، دسترسی هشدار دقیق را فعال کن."); w.setTextColor(Color.rgb(255,194,102)); w.setPadding(0,dp(10),0,0); p.addView(w);
            Button g=action("فعال‌کردن هشدار دقیق"); g.setOnClickListener(v->openExact()); p.addView(g);
        }

        p.addView(section("کارهای امروز"));
        List<DayTask> tasks=db.getDayTasks(today);
        if(tasks.isEmpty()) p.addView(small("برای امروز کاری برنامه‌ریزی نشده."));
        for(DayTask t:tasks) p.addView(taskRow(t));

        Button add=action("＋ کار جدید"); add.setOnClickListener(v->taskDialog(null)); p.addView(add);
        p.addView(section("یادداشت روزانه"));
        int period=LocalTime.now().getHour()<12?0:1;
        TextView pl=small(period==0?"نیمه اول روز · ۰۰:۰۰ تا ۱۲:۰۰":"نیمه دوم روز · ۱۲:۰۰ تا ۲۴:۰۰"); pl.setTextColor(ACCENT); p.addView(pl);
        notesEditor(p,today,period);


        sv.addView(p); host.addView(sv);
    }

    private View taskRow(DayTask t){
        LinearLayout row=card(); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox c=new CheckBox(this); c.setChecked(t.completed); c.setButtonTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{GOOD,MUTED}));
        row.addView(c,new LinearLayout.LayoutParams(dp(52),dp(56)));
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(4),dp(8),dp(8),dp(8));
        TextView name=new TextView(this); name.setText(t.title); name.setTextColor(t.completed?MUTED:TEXT); name.setTextSize(16); name.setGravity(Gravity.RIGHT);
        if(t.completed) name.setPaintFlags(name.getPaintFlags()|Paint.STRIKE_THRU_TEXT_FLAG);
        TaskItem ti=db.getTask(t.taskId);
        TextView meta=small(PersianDate.toPersianDigits(String.format(Locale.US,"%02d:%02d",t.hour,t.minute))+" · "+repeatText(ti));
        box.addView(name); box.addView(meta); row.addView(box,new LinearLayout.LayoutParams(0,-2,1));
        c.setOnClickListener(v->{db.setCompleted(t.taskId,LocalDate.now(),c.isChecked()); AlarmScheduler.cancelTask(this,t.taskId); AlarmScheduler.scheduleTask(this,t.taskId); showToday();});
        box.setOnClickListener(v->{TaskItem x=db.getTask(t.taskId); if(x!=null)taskDialog(x);});
        return row;
    }

    private void taskDialog(TaskItem old){
        LinearLayout f=new LinearLayout(this); f.setOrientation(LinearLayout.VERTICAL); f.setPadding(dp(18),dp(8),dp(18),0);
        EditText name=input("عنوان کار"); if(old!=null)name.setText(old.title); f.addView(name);
        final int[] tm={old==null?8:old.hour,old==null?0:old.minute};
        Button time=action(timeText(tm[0],tm[1])); time.setOnClickListener(v->new TimePickerDialog(this,(x,h,m)->{tm[0]=h;tm[1]=m;time.setText(timeText(h,m));},tm[0],tm[1],true).show()); f.addView(time);

        String[] labels={"ش","ی","د","س","چ","پ","ج"}; int[] cal={7,1,2,3,4,5,6};
        LinearLayout days=new LinearLayout(this); days.setOrientation(LinearLayout.HORIZONTAL); days.setGravity(Gravity.CENTER); final boolean[] sel=new boolean[7];
        for(int i=0;i<7;i++){final int k=i; Button b=compact(labels[i]); boolean on=old!=null&&(old.dayMask&(1<<cal[i]))!=0; sel[i]=on; markDay(b,on);
            b.setOnClickListener(v->{sel[k]=!sel[k];markDay(b,sel[k]);}); days.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));} f.addView(days);

        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(old==null?"کار جدید":"ویرایش کار").setView(f)
                .setPositiveButton("ذخیره",null).setNegativeButton("انصراف",null)
                .setNeutralButton(old==null?null:"حذف",null).create();
        dialog.setOnShowListener(x->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String s=name.getText().toString().trim(); if(s.isEmpty()){name.setError("عنوان را وارد کن");return;}
                int mask=0; for(int i=0;i<7;i++)if(sel[i])mask|=1<<cal[i];
                if(mask==0){Toast.makeText(this,"حداقل یک روز را انتخاب کن",Toast.LENGTH_SHORT).show();return;}
                if(old==null){long id=db.addTask(s,tm[0],tm[1],mask);AlarmScheduler.scheduleTask(this,id);}
                else{db.updateTask(old.id,s,tm[0],tm[1],mask);AlarmScheduler.cancelTask(this,old.id);AlarmScheduler.scheduleTask(this,old.id);}
                dialog.dismiss(); showToday();
            });
            if(old!=null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{AlarmScheduler.cancelTask(this,old.id);db.deleteTask(old.id);dialog.dismiss();showToday();});
        }); dialog.show();
    }

    private void markDay(Button b,boolean on){b.setTextColor(on?Color.rgb(10,20,31):TEXT); GradientDrawable g=new GradientDrawable();g.setColor(on?ACCENT:SURFACE2);g.setCornerRadius(dp(10));b.setBackground(g);}

    private void notesEditor(LinearLayout p,LocalDate date,int period){
        NoteRecord n=db.getNote(date,period);
        String[] heads={"من بابت این اتفاقات خوشحالم","چه چیزی می‌تونه باعث بشه بگم امروز روز خوبیه؟","جمله مثبت امروز","امروز چه اتفاق خوبی افتاد؟","امروز چی یاد گرفتم؟"};
        int[][] indexes={{0,1,2},{3,4,5},{6},{7},{8}};
        for(int g=0;g<heads.length;g++){p.addView(section(heads[g])); for(int idx:indexes[g]){
            EditText e=input("بنویس…"); e.setText(n.values[idx]); final int fi=idx;
            e.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){} public void onTextChanged(CharSequence s,int st,int b,int c){db.saveNoteField(date,period,fi,s.toString());} public void afterTextChanged(Editable e){}});
            p.addView(e);
        }}
    }

    private void showHistory(){
        screen=1; host.removeAllViews(); ScrollView sv=new ScrollView(this); LinearLayout p=page(); p.addView(title("تاریخچه"));
        List<String> dates=db.getHistoryDates();
        if(dates.isEmpty())p.addView(small("هنوز تاریخچه‌ای ثبت نشده."));
        for(String ds:dates){ LocalDate date=LocalDate.parse(ds); int[] c=db.getDayCounts(ds);
            Button b=compact(PersianDate.format(date)+"     "+PersianDate.toPersianDigits(c[1]+" از "+c[0])); b.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(58));lp.setMargins(0,dp(5),0,dp(5));p.addView(b,lp); b.setOnClickListener(v->historyDay(date));
        }
        sv.addView(p);host.addView(sv);
    }

    private void historyDay(LocalDate date){
        host.removeAllViews(); ScrollView sv=new ScrollView(this); LinearLayout p=page();
        Button back=compact("→ بازگشت"); back.setOnClickListener(v->showHistory()); p.addView(back,new LinearLayout.LayoutParams(-1,dp(48)));
        p.addView(title(PersianDate.formatWithWeekday(date))); p.addView(section("کارها"));
        for(DayTask t:db.getDayTasks(date)){TextView x=small((t.completed?"✓ ":"○ ")+t.title+" · "+timeText(t.hour,t.minute));x.setTextColor(t.completed?GOOD:TEXT);x.setTextSize(15);x.setPadding(0,dp(7),0,dp(7));p.addView(x);}
        for(int period=0;period<2;period++){NoteRecord n=db.getNote(date,period);if(!n.hasText())continue;p.addView(section(period==0?"یادداشت نیمه اول":"یادداشت نیمه دوم"));
            for(String s:n.values)if(s!=null&&!s.trim().isEmpty()){TextView x=small("• "+s);x.setTextColor(TEXT);x.setPadding(0,dp(5),0,dp(5));p.addView(x);}}
        sv.addView(p);host.addView(sv);
    }

    private void showAnalysisWeek(){
        LocalDate now=LocalDate.now(); LocalDate from=now.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)); showAnalysis(from,now,"این هفته");
    }

    private void showAnalysis(LocalDate from,LocalDate to,String mode){
        screen=2; host.removeAllViews(); ScrollView sv=new ScrollView(this); LinearLayout p=page(); p.addView(title("آنالیز"));
        LinearLayout controls=new LinearLayout(this); controls.setOrientation(LinearLayout.HORIZONTAL);
        Button w=compact("این هفته"),m=compact("این ماه"),c=compact("بازه دلخواه");
        controls.addView(w,new LinearLayout.LayoutParams(0,dp(52),1));controls.addView(m,new LinearLayout.LayoutParams(0,dp(52),1));controls.addView(c,new LinearLayout.LayoutParams(0,dp(52),1));p.addView(controls);
        w.setOnClickListener(v->showAnalysisWeek()); m.setOnClickListener(v->{int[] j=PersianDate.fromGregorian(LocalDate.now());showAnalysis(PersianDate.toGregorian(j[0],j[1],1),LocalDate.now(),"این ماه");}); c.setOnClickListener(v->customRange());
        TextView r=small(mode+" · "+PersianDate.format(from)+" تا "+PersianDate.format(to));r.setTextColor(ACCENT);r.setPadding(0,dp(12),0,dp(14));p.addView(r);
        int[] total=db.analyzeTotal(from,to);int pct=total[0]==0?0:Math.round(total[1]*100f/total[0]);
        TextView big=title(PersianDate.toPersianDigits(pct+"%"));big.setTextSize(44);big.setGravity(Gravity.CENTER);p.addView(big);
        TextView sub=small(PersianDate.toPersianDigits(total[1]+" انجام‌شده از "+total[0]+" کار"));sub.setGravity(Gravity.CENTER);p.addView(sub);
        for(DbHelper.AnalysisRow ar:db.analyze(from,to)){LinearLayout box=card();box.setOrientation(LinearLayout.VERTICAL);int rate=(int)Math.round(ar.rate());
            TextView t=small(ar.title+"     "+PersianDate.toPersianDigits(rate+"%"));t.setTextColor(TEXT);t.setTextSize(15);box.addView(t);
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgress(rate);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));box.addView(bar,new LinearLayout.LayoutParams(-1,dp(12)));
            box.addView(small(PersianDate.toPersianDigits(ar.completed+" از "+ar.planned+" بار")));p.addView(box);}
        sv.addView(p);host.addView(sv);
    }

    private void customRange(){
        LocalDate now=LocalDate.now(); final LocalDate[] x={now.minusDays(7),now};
        DatePickerDialog a=new DatePickerDialog(this,(v,y,m,d)->{x[0]=LocalDate.of(y,m+1,d);
            DatePickerDialog z=new DatePickerDialog(this,(v2,y2,m2,d2)->{x[1]=LocalDate.of(y2,m2+1,d2);if(x[1].isBefore(x[0])){Toast.makeText(this,"تاریخ پایان قبل از شروع است",Toast.LENGTH_SHORT).show();return;}showAnalysis(x[0],x[1],"بازه دلخواه");},x[1].getYear(),x[1].getMonthValue()-1,x[1].getDayOfMonth());z.show();
        },x[0].getYear(),x[0].getMonthValue()-1,x[0].getDayOfMonth());a.show();
    }


    private void showBackupRestore(){
        screen=3;
        host.removeAllViews();
        ScrollView sv=new ScrollView(this);
        LinearLayout p=page();

        p.addView(title("Backup & Restore"));
        TextView intro=small("پشتیبان‌ها فقط در فایلی که خودت انتخاب می‌کنی ذخیره می‌شوند و به هیچ سروری ارسال نمی‌شوند.");
        intro.setTextColor(Color.rgb(159,174,205));
        intro.setPadding(0,dp(6),0,dp(16));
        p.addView(intro);

        LinearLayout full=card();
        TextView fullTitle=menuHeading("Full Backup");
        full.addView(fullTitle);
        full.addView(small("همه کارها، تمام تاریخچه و همه یادداشت‌ها"));
        Button fullBtn=action("Create Full Backup");
        fullBtn.setOnClickListener(v->startBackup(true,null,null));
        full.addView(fullBtn);
        p.addView(full);

        LinearLayout range=card();
        range.addView(menuHeading("Date Range Backup"));
        range.addView(small("فقط تاریخچه و یادداشت‌های بازه‌ای که انتخاب می‌کنی"));

        Button from=compact("از: "+PersianDate.format(rangeFrom));
        Button to=compact("تا: "+PersianDate.format(rangeTo));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(52));
        bp.setMargins(0,dp(8),0,dp(4));
        range.addView(from,bp);
        LinearLayout.LayoutParams bp2=new LinearLayout.LayoutParams(-1,dp(52));
        bp2.setMargins(0,dp(4),0,dp(8));
        range.addView(to,bp2);

        from.setOnClickListener(v->pickDate(rangeFrom,d->{rangeFrom=d;showBackupRestore();}));
        to.setOnClickListener(v->pickDate(rangeTo,d->{rangeTo=d;showBackupRestore();}));

        Button rangeBtn=action("Create Range Backup");
        rangeBtn.setOnClickListener(v->{
            if(rangeTo.isBefore(rangeFrom)){
                Toast.makeText(this,"تاریخ پایان قبل از شروع است",Toast.LENGTH_SHORT).show();
                return;
            }
            startBackup(false,rangeFrom,rangeTo);
        });
        range.addView(rangeBtn);
        p.addView(range);

        LinearLayout restore=card();
        restore.addView(menuHeading("Restore"));
        restore.addView(small("Full Backup جایگزین کامل است؛ بکاپ بازه‌ای با تاریخچه فعلی ادغام می‌شود."));
        Button restoreBtn=compact("Choose Backup File");
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(52));
        rp.setMargins(0,dp(10),0,0);
        restore.addView(restoreBtn,rp);
        restoreBtn.setOnClickListener(v->chooseRestoreFile());
        p.addView(restore);

        sv.addView(p);
        host.addView(sv);
    }

    private void startBackup(boolean full,LocalDate from,LocalDate to){
        pendingBackupType=full?"full":"range";
        pendingBackupFrom=from;
        pendingBackupTo=to;

        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        String name=full
                ?"GoalsReminder_Full_"+LocalDate.now()+".grbackup"
                :"GoalsReminder_"+from+"_to_"+to+".grbackup";
        i.putExtra(Intent.EXTRA_TITLE,name);
        startActivityForResult(i,REQ_EXPORT_BACKUP);
    }

    private void chooseRestoreFile(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i,REQ_IMPORT_BACKUP);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        if(requestCode==REQ_EXPORT_BACKUP)exportBackup(uri);
        else if(requestCode==REQ_IMPORT_BACKUP)prepareRestore(uri);
    }

    private void exportBackup(Uri uri){
        try{
            boolean full="full".equals(pendingBackupType);
            JSONObject json=db.createBackup(pendingBackupFrom,pendingBackupTo,full);
            try(OutputStream out=getContentResolver().openOutputStream(uri,"w");
                OutputStreamWriter writer=new OutputStreamWriter(out,StandardCharsets.UTF_8)){
                writer.write(json.toString(2));
                writer.flush();
            }
            Toast.makeText(this,full?"Full Backup ساخته شد":"بکاپ بازه زمانی ساخته شد",Toast.LENGTH_LONG).show();
        }catch(Exception e){
            Toast.makeText(this,"ساخت فایل پشتیبان ناموفق بود",Toast.LENGTH_LONG).show();
        }
    }

    private void prepareRestore(Uri uri){
        try{
            StringBuilder sb=new StringBuilder();
            try(InputStream in=getContentResolver().openInputStream(uri);
                BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){
                String line;
                while((line=reader.readLine())!=null)sb.append(line).append('\n');
            }
            JSONObject json=new JSONObject(sb.toString());
            if(!"GoalsReminderBackup".equals(json.optString("format")))throw new IllegalArgumentException("invalid");
            boolean full="full".equals(json.optString("type"));
            String message=full
                    ?"این Full Backup اطلاعات فعلی برنامه را جایگزین می‌کند. ادامه می‌دهی؟"
                    :"این بکاپ بازه‌ای با تاریخچه و یادداشت‌های فعلی ادغام می‌شود. ادامه می‌دهی؟";
            new AlertDialog.Builder(this)
                    .setTitle("Restore Backup")
                    .setMessage(message)
                    .setPositiveButton("بازیابی",(d,w)->restoreParsedBackup(json))
                    .setNegativeButton("انصراف",null)
                    .show();
        }catch(Exception e){
            Toast.makeText(this,"فایل پشتیبان معتبر نیست",Toast.LENGTH_LONG).show();
        }
    }

    private void restoreParsedBackup(JSONObject json){
        try{
            String type=db.restoreBackup(json);
            AlarmScheduler.scheduleAll(this);
            Toast.makeText(this,"full".equals(type)?"Full Backup بازیابی شد":"بازه تاریخی بازیابی و ادغام شد",Toast.LENGTH_LONG).show();
            render();
        }catch(Exception e){
            Toast.makeText(this,"بازیابی ناموفق بود",Toast.LENGTH_LONG).show();
        }
    }

    private void pickDate(LocalDate initial,java.util.function.Consumer<LocalDate> result){
        new DatePickerDialog(this,(v,y,m,d)->result.accept(LocalDate.of(y,m+1,d)),
                initial.getYear(),initial.getMonthValue()-1,initial.getDayOfMonth()).show();
    }

    private void showSettings(){
        screen=4;
        host.removeAllViews();
        ScrollView sv=new ScrollView(this);
        LinearLayout p=page();

        p.addView(title("Settings"));

        LinearLayout langCard=card();
        langCard.addView(menuHeading("Language"));
        langCard.addView(small(tr("زبان نمایش برنامه را انتخاب کن.","Choose the app display language.")));

        Button fa=compact("فارسی");
        Button enBtn=compact("English");
        if(!en())fa.setBackground(accentDrawable());
        if(en())enBtn.setBackground(accentDrawable());

        LinearLayout choices=new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(54),1);
        lp.setMargins(dp(3),dp(10),dp(3),0);
        choices.addView(fa,lp);
        LinearLayout.LayoutParams lp2=new LinearLayout.LayoutParams(0,dp(54),1);
        lp2.setMargins(dp(3),dp(10),dp(3),0);
        choices.addView(enBtn,lp2);
        langCard.addView(choices);

        fa.setOnClickListener(v->setLanguage("fa"));
        enBtn.setOnClickListener(v->setLanguage("en"));
        p.addView(langCard);

        LinearLayout offline=card();
        offline.addView(menuHeading("Offline & Privacy"));
        offline.addView(small(tr(
                "برنامه برای استفاده روزمره به اینترنت نیاز ندارد. کارها، یادداشت‌ها و تاریخچه روی خود گوشی ذخیره می‌شوند.",
                "The app does not need internet for normal use. Tasks, notes and history are stored locally on this device.")));
        p.addView(offline);

        sv.addView(p);
        host.addView(sv);
    }

    private void setLanguage(String value){
        language=value;
        getSharedPreferences("goals_settings",MODE_PRIVATE).edit().putString("language",value).apply();
        buildDrawer();
        showSettings();
    }

    private boolean en(){
        return "en".equals(language);
    }

    private String tr(String fa,String english){
        return en()?english:fa;
    }

    private String localizedDate(LocalDate date,boolean weekday){
        if(!en())return weekday?PersianDate.formatWithWeekday(date):PersianDate.format(date);
        DateTimeFormatter f=DateTimeFormatter.ofPattern(weekday?"EEEE, MMM d, yyyy":"MMM d, yyyy",Locale.ENGLISH);
        return date.format(f);
    }

    private String localNumber(String value){
        return en()?value:PersianDate.toPersianDigits(value);
    }

    private boolean canExact(){if(Build.VERSION.SDK_INT<Build.VERSION_CODES.S)return true;return ((AlarmManager)getSystemService(ALARM_SERVICE)).canScheduleExactAlarms();}
    private void openExact(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:"+getPackageName())));}catch(Exception e){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}}
    private String repeatText(TaskItem t){if(t==null)return "";String[] l={"ی","د","س","چ","پ","ج","ش"};int[] c={1,2,3,4,5,6,7};List<String> a=new ArrayList<>();for(int i=0;i<7;i++)if((t.dayMask&(1<<c[i]))!=0)a.add(l[i]);return a.size()==7?"هر روز":String.join("، ",a);}
    private String timeText(int h,int m){return PersianDate.toPersianDigits(String.format(Locale.US,"%02d:%02d",h,m));}

    private LinearLayout page(){
        LinearLayout p=new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        p.setPadding(dp(18),dp(18),dp(18),dp(44));
        return p;
    }

    private LinearLayout card(){
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(15),dp(13),dp(15),dp(13));
        l.setBackground(glassDrawable(190,18));
        l.setElevation(dp(5));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(7),0,dp(7));
        l.setLayoutParams(lp);
        return l;
    }

    private TextView title(String s){
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(29);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.RIGHT);
        return t;
    }

    private TextView section(String s){
        TextView t=title(s);
        t.setTextSize(20);
        t.setPadding(0,dp(24),0,dp(8));
        return t;
    }

    private TextView menuHeading(String s){
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextColor(TEXT);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.LEFT);
        t.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        t.setPadding(0,0,0,dp(5));
        return t;
    }

    private TextView small(String s){
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextColor(MUTED);
        t.setTextSize(13);
        t.setGravity(Gravity.RIGHT);
        return t;
    }

    private EditText input(String hint){
        EditText e=new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(121,133,156));
        e.setTextColor(TEXT);
        e.setGravity(Gravity.RIGHT);
        e.setPadding(dp(14),dp(11),dp(14),dp(11));
        e.setBackground(inputDrawable());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(5),0,dp(5));
        e.setLayoutParams(lp);
        return e;
    }

    private Button action(String s){
        Button b=new Button(this);
        b.setText(s);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(accentDrawable());
        b.setElevation(dp(5));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));
        lp.setMargins(0,dp(10),0,dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private Button compact(String s){
        Button b=new Button(this);
        b.setText(s);
        b.setTextColor(TEXT);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setBackground(glassDrawable(195,12));
        return b;
    }

    private GradientDrawable appBackground(){
        return new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(7,10,18),Color.rgb(10,17,31),Color.rgb(18,12,35)});
    }

    private GradientDrawable glassDrawable(int alpha,int radius){
        GradientDrawable g=new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(alpha,34,43,66),Color.argb(Math.max(120,alpha-35),18,24,39)});
        g.setCornerRadius(dp(radius));
        g.setStroke(dp(1),Color.argb(68,154,179,235));
        return g;
    }

    private GradientDrawable inputDrawable(){
        GradientDrawable g=new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(198,37,45,67),Color.argb(186,25,31,49)});
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1),Color.argb(55,176,196,235));
        return g;
    }

    private GradientDrawable accentDrawable(){
        GradientDrawable g=new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT,Color.rgb(76,120,255),PURPLE});
        g.setCornerRadius(dp(15));
        g.setStroke(dp(1),Color.argb(125,196,213,255));
        return g;
    }

    private GradientDrawable menuItemDrawable(boolean active){
        GradientDrawable g=new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                active
                        ?new int[]{Color.argb(215,38,119,255),Color.argb(205,106,70,255)}
                        :new int[]{Color.argb(90,42,51,72),Color.argb(70,24,30,47)});
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1),active?Color.argb(130,176,205,255):Color.argb(42,155,177,220));
        return g;
    }

    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

}
