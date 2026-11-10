package ch.finanztracker.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.Locale;

public class WidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) updateOne(context, manager, id);
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, WidgetProvider.class));
        for (int id : ids) updateOne(context, manager, id);
    }

    private static void updateOne(Context context, AppWidgetManager manager, int id) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_finance);
        MainActivity.DB db = new MainActivity.DB(context);
        ArrayList<String> names = db.accounts();
        String last = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
                .getString("last_account_name", "");
        int accountId = db.accountId(last);
        if (accountId < 0 && !names.isEmpty()) {
            last = names.get(0);
            accountId = db.accountId(last);
        }
        double accountBalance = accountId > 0 ? db.accountBalance(accountId) : 0;
        double total = 0;
        for (String name : names) total += db.accountBalance(db.accountId(name));
        views.setTextViewText(R.id.widget_account_name, names.isEmpty() ? "Kein Konto" : last);
        views.setTextViewText(R.id.widget_account_balance, money(accountBalance));
        views.setTextViewText(R.id.widget_total_balance, "Gesamtsaldo: " + money(total));

        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(context, id * 10, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, openPending);

        Intent income = new Intent(context, MainActivity.class);
        income.putExtra("widget_action", "income");
        income.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent incomePending = PendingIntent.getActivity(context, id * 10 + 1, income,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_income, incomePending);

        Intent expense = new Intent(context, MainActivity.class);
        expense.putExtra("widget_action", "expense");
        expense.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent expensePending = PendingIntent.getActivity(context, id * 10 + 2, expense,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_expense, expensePending);
        manager.updateAppWidget(id, views);
        db.close();
    }

    private static String money(double value) {
        return String.format(Locale.GERMANY, "CHF %,.2f", value);
    }
}
