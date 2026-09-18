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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends android.app.Activity {
    private static final int BG=Color.rgb(11,14,19), SURFACE=Color.rgb(24,28,36),
            SURFACE2=Color.rgb(34,39,49), TEXT=Color.rgb(238,241,246),
            MUTED=Color.rgb(155,164,178), ACCENT=Color.rgb(138,180,248),
            GOOD=Color.rgb(93,200,139);
    private DbHelper db;
    private FrameLayout host;
    private int screen=0;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
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

    private void buildShell(){
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(BG); root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        host=new FrameLayout(this);
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,-1); hp.setMargins(0,0,dp(76),0); root.addView(host,hp);

        LinearLayout rail=new LinearLayout(this); rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL); rail.setPadding(dp(5),dp(18),dp(5),0); rail.setBackgroundColor(Color.rgb(17,20,27));
        Button a=rail("☷\nامروز"), h=rail("◷\nتاریخچه"), n=rail("▥\nآنالیز");
        rail.addView(a); rail.addView(h); rail.addView(n);
        a.setOnClickListener(v->showToday()); h.setOnClickListener(v->showHistory()); n.setOnClickListener(v->showAnalysisWeek());
        root.addView(rail,new FrameLayout.LayoutParams(dp(76),-1,Gravity.END));
        setContentView(root);
    }

    private Button rail(String s){
        Button b=new Button(this); b.setText(s); b.setTextColor(TEXT); b.setTextSize(11); b.setAllCaps(false);
        b.setGravity(Gravity.CENTER); b.setBackgroundColor(Color.TRANSPARENT);
        b.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(82))); return b;
    }

    private void render(){ if(host==null)return; if(screen==0)showToday(); else if(screen==1)showHistory(); else showAnalysisWeek(); }

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

    private boolean canExact(){if(Build.VERSION.SDK_INT<Build.VERSION_CODES.S)return true;return ((AlarmManager)getSystemService(ALARM_SERVICE)).canScheduleExactAlarms();}
    private void openExact(){if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)try{startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:"+getPackageName())));}catch(Exception e){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}}
    private String repeatText(TaskItem t){if(t==null)return "";String[] l={"ی","د","س","چ","پ","ج","ش"};int[] c={1,2,3,4,5,6,7};List<String> a=new ArrayList<>();for(int i=0;i<7;i++)if((t.dayMask&(1<<c[i]))!=0)a.add(l[i]);return a.size()==7?"هر روز":String.join("، ",a);}
    private String timeText(int h,int m){return PersianDate.toPersianDigits(String.format(Locale.US,"%02d:%02d",h,m));}

    private LinearLayout page(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);p.setPadding(dp(18),dp(18),dp(18),dp(40));return p;}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setPadding(dp(12),dp(8),dp(12),dp(8));GradientDrawable g=new GradientDrawable();g.setColor(SURFACE);g.setCornerRadius(dp(14));l.setBackground(g);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(6));l.setLayoutParams(lp);return l;}
    private TextView title(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(TEXT);t.setTextSize(28);t.setTypeface(Typeface.DEFAULT_BOLD);t.setGravity(Gravity.RIGHT);return t;}
    private TextView section(String s){TextView t=title(s);t.setTextSize(18);t.setPadding(0,dp(22),0,dp(8));return t;}
    private TextView small(String s){TextView t=new TextView(this);t.setText(s);t.setTextColor(MUTED);t.setTextSize(13);t.setGravity(Gravity.RIGHT);return t;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(MUTED);e.setTextColor(TEXT);e.setGravity(Gravity.RIGHT);e.setPadding(dp(12),dp(9),dp(12),dp(9));GradientDrawable g=new GradientDrawable();g.setColor(SURFACE2);g.setCornerRadius(dp(11));e.setBackground(g);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,dp(5));e.setLayoutParams(lp);return e;}
    private Button action(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.rgb(10,20,31));b.setAllCaps(false);b.setTypeface(Typeface.DEFAULT_BOLD);GradientDrawable g=new GradientDrawable();g.setColor(ACCENT);g.setCornerRadius(dp(12));b.setBackground(g);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.setMargins(0,dp(10),0,dp(8));b.setLayoutParams(lp);return b;}
    private Button compact(String s){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setTextSize(12);b.setAllCaps(false);GradientDrawable g=new GradientDrawable();g.setColor(SURFACE2);g.setCornerRadius(dp(10));b.setBackground(g);return b;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
