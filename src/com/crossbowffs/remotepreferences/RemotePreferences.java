package com.crossbowffs.remotepreferences;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;

import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Provides a {@link SharedPreferences} compatible API to
 * {@link RemotePreferenceProvider}. See {@link RemotePreferenceProvider}
 * for more information.
 */
public class RemotePreferences implements SharedPreferences {
    private final Context mContext;
    private final Handler mHandler;
    private final Uri mBaseUri;
    private final WeakHashMap<OnSharedPreferenceChangeListener, PreferenceContentObserver> mListeners;
    private final boolean mStrictMode;

    /**
     * Initializes a new remote preferences object, with strict
     * mode disabled.
     *
     * @param context Used to access the preference provider.
     * @param authority The authority of the preference provider.
     * @param prefName The name of the preference file to access.
     */
    public RemotePreferences(Context context, String authority, String prefName) {
        this(context, authority, prefName, false);
    }

    /**
     * Initializes a new remote preferences object. If {@code strictMode}
     * is {@code true} and the remote preference provider cannot be accessed,
     * read/write operations on this object will throw a
     * {@link RemotePreferenceAccessException}. Otherwise, default values
     * will be returned.
     *
     * @param context Used to access the preference provider.
     * @param authority The authority of the preference provider.
     * @param prefName The name of the preference file to access.
     * @param strictMode Whether strict mode is enabled.
     */
    public RemotePreferences(Context context, String authority, String prefName, boolean strictMode) {
        checkNotNull("authority", authority);
        checkNotNull("context", context);
        checkNotNull("prefName", prefName);
        mContext = context;
        mHandler = new Handler(context.getMainLooper());
        mBaseUri = Uri.parse("content://" + authority).buildUpon().appendPath(prefName).build();
        mListeners = new WeakHashMap<OnSharedPreferenceChangeListener, PreferenceContentObserver>();
        mStrictMode = strictMode;
    }

    @Override
    public Map<String, ?> getAll() {
        return queryAll();
    }

    @Override
    public String getString(String key, String defValue) {
        return (String)querySingle(key, defValue, RemoteContract.TYPE_STRING);
    }

    @Override
    @TargetApi(11)
    public Set<String> getStringSet(String key, Set<String> defValues) {
        if (Build.VERSION.SDK_INT < 11) {
            throw new UnsupportedOperationException("String sets only supported on API 11 and above");
        }
        return RemoteUtils.castStringSet(querySingle(key, defValues, RemoteContract.TYPE_STRING_SET));
    }

    @Override
    public int getInt(String key, int defValue) {
        return (Integer)querySingle(key, defValue, RemoteContract.TYPE_INT);
    }

    @Override
    public long getLong(String key, long defValue) {
        return (Long)querySingle(key, defValue, RemoteContract.TYPE_LONG);
    }

    @Override
    public float getFloat(String key, float defValue) {
        return (Float)querySingle(key, defValue, RemoteContract.TYPE_FLOAT);
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        return (Boolean)querySingle(key, defValue, RemoteContract.TYPE_BOOLEAN);
    }

    @Override
    public boolean contains(String key) {
        return containsKey(key);
    }

    @Override
    public Editor edit() {
        return new RemotePreferencesEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        checkNotNull("listener", listener);
        if (mListeners.containsKey(listener)) return;
        PreferenceContentObserver observer = new PreferenceContentObserver(listener);
        mListeners.put(listener, observer);
        mContext.getContentResolver().registerContentObserver(mBaseUri, true, observer);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        checkNotNull("listener", listener);
        PreferenceContentObserver observer = mListeners.remove(listener);
        if (observer != null) {
            mContext.getContentResolver().unregisterContentObserver(observer);
        }
    }

    private void checkNotNull(String name, Object object) {
        if (object == null) {
            throw new IllegalArgumentException(name + " is null");
        }
    }

    private void checkKeyNotEmpty(String key) {
        if (key == null || key.length() == 0) {
            throw new IllegalArgumentException("Key is null or empty");
        }
    }

    private boolean wrapException(Exception e) {
        if (mStrictMode) {
            throw new RemotePreferenceAccessException(e);
        } else {
            return false;
        }
    }

    private Cursor query(Uri uri, String[] columns) {
        Cursor cursor = null;
        try {
            cursor = mContext.getContentResolver().query(uri, columns, null, null, null);
        } catch (SecurityException e) {
            if (mStrictMode) {
                throw new RemotePreferenceAccessException(e);
            }
        }
        if (cursor == null && mStrictMode) {
            throw new RemotePreferenceAccessException("query() returned null cursor");
        }
        return cursor;
    }

    private boolean delete(Uri uri) {
        try {
            mContext.getContentResolver().delete(uri, null, null);
        } catch (SecurityException e) {
            return wrapException(e);
        } catch (IllegalArgumentException e) {
            return wrapException(e);
        }
        return true;
    }

    private boolean bulkInsert(Uri uri, ContentValues[] values) {
        try {
            mContext.getContentResolver().bulkInsert(uri, values);
        } catch (SecurityException e) {
            return wrapException(e);
        } catch (IllegalArgumentException e) {
            return wrapException(e);
        }
        return true;
    }

    private Object querySingle(String key, Object defValue, int expectedType) {
        checkKeyNotEmpty(key);
        Uri uri = mBaseUri.buildUpon().appendPath(key).build();
        String[] columns = {RemoteContract.COLUMN_TYPE, RemoteContract.COLUMN_VALUE};
        Cursor cursor = query(uri, columns);
        try {
            if (cursor == null || !cursor.moveToFirst() || cursor.getInt(0) == RemoteContract.TYPE_NULL) {
                return defValue;
            } else if (cursor.getInt(0) != expectedType) {
                throw new ClassCastException("Preference type mismatch");
            } else {
                return getValue(cursor, 0, 1);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private Map<String, Object> queryAll() {
        Uri uri = mBaseUri.buildUpon().appendPath("").build();
        String[] columns = {RemoteContract.COLUMN_KEY, RemoteContract.COLUMN_TYPE, RemoteContract.COLUMN_VALUE};
        Cursor cursor = query(uri, columns);
        try {
            HashMap<String, Object> map = new HashMap<String, Object>();
            if (cursor == null) {
                return map;
            }
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                map.put(name, getValue(cursor, 1, 2));
            }
            return map;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private boolean containsKey(String key) {
        checkKeyNotEmpty(key);
        Uri uri = mBaseUri.buildUpon().appendPath(key).build();
        String[] columns = {RemoteContract.COLUMN_TYPE};
        Cursor cursor = query(uri, columns);
        try {
            return (cursor != null && cursor.moveToFirst() && cursor.getInt(0) != RemoteContract.TYPE_NULL);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private Object getValue(Cursor cursor, int typeCol, int valueCol) {
        int expectedType = cursor.getInt(typeCol);
        switch (expectedType) {
        case RemoteContract.TYPE_STRING:
            return cursor.getString(valueCol);
        case RemoteContract.TYPE_STRING_SET:
            return RemoteUtils.deserializeStringSet(cursor.getString(valueCol));
        case RemoteContract.TYPE_INT:
            return cursor.getInt(valueCol);
        case RemoteContract.TYPE_LONG:
            return cursor.getLong(valueCol);
        case RemoteContract.TYPE_FLOAT:
            return cursor.getFloat(valueCol);
        case RemoteContract.TYPE_BOOLEAN:
            return cursor.getInt(valueCol) != 0;
        default:
            throw new AssertionError("Invalid expected type: " + expectedType);
        }
    }

    private class RemotePreferencesEditor implements Editor {
        private final ArrayList<ContentValues> mToAdd = new ArrayList<ContentValues>();
        private final HashSet<String> mToRemove = new HashSet<String>();

        private ContentValues add(String key, int type) {
            checkKeyNotEmpty(key);
            ContentValues values = new ContentValues(4); // 3 keys / 0.75 resize factor
            values.put(RemoteContract.COLUMN_KEY, key);
            values.put(RemoteContract.COLUMN_TYPE, type);
            mToAdd.add(values);
            return values;
        }

        @Override
        public Editor putString(String key, String value) {
            add(key, RemoteContract.TYPE_STRING)
                .put(RemoteContract.COLUMN_VALUE, value);
            return this;
        }

        @Override
        @TargetApi(11)
        public Editor putStringSet(String key, Set<String> value) {
            if (Build.VERSION.SDK_INT < 11) {
                throw new UnsupportedOperationException("String sets only supported on API 11 and above");
            }
            add(key, RemoteContract.TYPE_STRING_SET)
                .put(RemoteContract.COLUMN_VALUE, RemoteUtils.serializeStringSet(value));
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            add(key, RemoteContract.TYPE_INT)
                .put(RemoteContract.COLUMN_VALUE, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            add(key, RemoteContract.TYPE_LONG)
                .put(RemoteContract.COLUMN_VALUE, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            add(key, RemoteContract.TYPE_FLOAT)
                .put(RemoteContract.COLUMN_VALUE, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            add(key, RemoteContract.TYPE_BOOLEAN)
                .put(RemoteContract.COLUMN_VALUE, value ? 1 : 0);
            return this;
        }

        @Override
        public Editor remove(String key) {
            checkKeyNotEmpty(key);
            mToRemove.add(key);
            return this;
        }

        @Override
        public Editor clear() {
            mToRemove.add("");
            return this;
        }

        @Override
        public boolean commit() {
            // WARNING: This method may corrupt preference state if any
            // but the first operation fails. There is no good solution
            // to this (applyBatch doesn't provide a sufficient API), so
            // we'll just have to pray that all exceptions are thrown on
            // the first access.
            for (String key : mToRemove) {
                Uri uri = mBaseUri.buildUpon().appendPath(key).build();
                if (!delete(uri)) {
                    return false;
                }
            }
            ContentValues[] values = mToAdd.toArray(new ContentValues[mToAdd.size()]);
            Uri uri = mBaseUri.buildUpon().appendPath("").build();
            return bulkInsert(uri, values);
        }

        @Override
        public void apply() {
            commit();
        }
    }

    private class PreferenceContentObserver extends ContentObserver {
        private final WeakReference<OnSharedPreferenceChangeListener> mListener;

        private PreferenceContentObserver(OnSharedPreferenceChangeListener listener) {
            super(mHandler);
            mListener = new WeakReference<OnSharedPreferenceChangeListener>(listener);
        }

        @Override
        public boolean deliverSelfNotifications() {
            return true;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            String prefKey = uri.getLastPathSegment();
            OnSharedPreferenceChangeListener listener = mListener.get();
            if (listener == null) {
                mContext.getContentResolver().unregisterContentObserver(this);
            } else {
                listener.onSharedPreferenceChanged(RemotePreferences.this, prefKey);
            }
        }
    }
}
