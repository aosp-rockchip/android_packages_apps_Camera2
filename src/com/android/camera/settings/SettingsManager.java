/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.preference.PreferenceManager;

import com.android.camera.debug.Log;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;


/**
 * SettingsManager class provides an api for getting and setting SharedPreferences
 * values.
 *
 * Types
 *
 * This API simplifies settings type management by storing all settings values
 * in SharedPreferences as Strings.  To do this, the API to converts boolean and
 * Integer values to Strings when those values are stored, making the conversion
 * back to a boolean or Integer also consistent and simple.
 *
 * This also enables the user to safely get settings values as three different types,
 * as it's convenient: String, Integer, and boolean values.  Integers and boolean
 * can always be trivially converted to one another, but Strings cannot always be
 * parsed as Integers.  In this case, if the user stores a String value that cannot
 * be parsed to an Integer yet they try to retrieve it as an Integer, the API throws
 * a meaningful exception to the user.
 *
 * Scope
 *
 * This API introduces the concept of "scope" for a setting, which is the generality
 * of a setting.  The most general settings, that can be accessed acrossed the
 * entire application, have a scope of SCOPE_GLOBAL.  They are stored in the default
 * SharedPreferences file.
 *
 * A setting that is local to a third party module or subset of the application has
 * a custom scope.  The specific module can define whatever scope (String) argument
 * they want, and the settings saved with that scope can only be seen by that third
 * party module.  Scope is a general concept that helps protect settings values
 * from being clobbered in different contexts.
 *
 * Keys and Defaults
 *
 * This API allows you to store your SharedPreferences keys and default values
 * outside the SettingsManager, because these values are either passed into
 * the API or stored in a cache when the user sets defaults.
 *
 * For any setting, it is optional to store a default or set of possible values,
 * unless you plan on using the getIndexOfCurrentValue and setValueByIndex,
 * methods, which rely on an index into the set of possible values.
 *
 */
@ThreadSafe
public class SettingsManager {
    private static final Log.Tag TAG = new Log.Tag("SettingsManager");

    private final Object mLock;
    private final Context mContext;
    private final String mPackageName;
    private final SharedPreferences mDefaultPreferences;
    private SharedPreferences mCustomPreferences;
    private final DefaultsStore mDefaultsStore = new DefaultsStore();

    public static final String MODULE_SCOPE_PREFIX = "_preferences_module_";
    public static final String CAMERA_SCOPE_PREFIX = "_preferences_camera_";

    /**
     * A List of OnSettingChangedListener's, maintained to compare to new
     * listeners and prevent duplicate registering.
     */
    private final List<OnSettingChangedListener> mListeners =
        new ArrayList<OnSettingChangedListener>();

    /**
     * A List of OnSharedPreferenceChangeListener's, maintained to hold pointers
     * to actually registered listeners, so they can be unregistered.
     */
    private final List<OnSharedPreferenceChangeListener> mSharedPreferenceListeners =
        new ArrayList<OnSharedPreferenceChangeListener>();

    public SettingsManager(Context context) {
        mLock = new Object();
        mContext = context;
        mPackageName = mContext.getPackageName();

        mDefaultPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    /**
     * Get the SettingsManager's default preferences.  This is useful
     * to third party modules as they are defining their upgrade paths,
     * since most third party modules will use either SCOPE_GLOBAL or a
     * custom scope.
     */
    public SharedPreferences getDefaultPreferences() {
        synchronized (mLock) {
            return mDefaultPreferences;
        }
    }

    /**
     * Open a SharedPreferences file by custom scope.
     * Also registers any known SharedPreferenceListeners on this
     * SharedPreferences instance.
     */
    protected SharedPreferences openPreferences(String scope) {
        synchronized (mLock) {
            SharedPreferences preferences;
            // For external camera, scope could have "/" separator which is a invalid path
            // for the shared preference.
            String validScope = scope.replaceAll("/", "_");
            preferences = mContext.getSharedPreferences(
                    mPackageName + validScope, Context.MODE_PRIVATE);

            for (OnSharedPreferenceChangeListener listener : mSharedPreferenceListeners) {
                preferences.registerOnSharedPreferenceChangeListener(listener);
            }
            return preferences;
        }
    }

    /**
     * Close a SharedPreferences file by custom scope.
     * The file isn't explicitly closed (the SharedPreferences API makes
     * this unnecessary), so the real work is to unregister any known
     * SharedPreferenceListeners from this SharedPreferences instance.
     *
     * It's important to do this as camera and modules change, because
     * we don't want old SharedPreferences listeners executing on
     * cameras/modules they are not compatible with.
     */
    protected void closePreferences(SharedPreferences preferences) {
        synchronized (mLock) {
            for (OnSharedPreferenceChangeListener listener : mSharedPreferenceListeners) {
                preferences.unregisterOnSharedPreferenceChangeListener(listener);
            }
        }
    }

    public static String getCameraSettingScope(String cameraIdValue) {
        cameraIdValue = cameraIdValue.replaceAll("/", "_");
        return CAMERA_SCOPE_PREFIX + cameraIdValue;
    }

    public static String getModuleSettingScope(String moduleScopeNamespace) {
        return CAMERA_SCOPE_PREFIX + moduleScopeNamespace;
    }

    /**
     * Interface with Camera Device Settings and Modules.
     */
    public interface OnSettingChangedListener {
        /**
         * Called every time a SharedPreference has been changed.
         */
        public void onSettingChanged(SettingsManager settingsManager, String key);
    }

    private OnSharedPreferenceChangeListener getSharedPreferenceListener(
            final OnSettingChangedListener listener) {
        return new OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(
                    SharedPreferences sharedPreferences, String key) {
                listener.onSettingChanged(SettingsManager.this, key);
            }
        };
    }

    /**
     * Add an OnSettingChangedListener to the SettingsManager, which will
     * execute onSettingsChanged when any SharedPreference has been updated.
     */
    public void addListener(final OnSettingChangedListener listener) {
        synchronized (mLock) {
            if (listener == null) {
                throw new IllegalArgumentException("OnSettingChangedListener cannot be null.");
            }

            if (mListeners.contains(listener)) {
                return;
            }

            mListeners.add(listener);
            OnSharedPreferenceChangeListener sharedPreferenceListener =
                    getSharedPreferenceListener(listener);
            mSharedPreferenceListeners.add(sharedPreferenceListener);
            mDefaultPreferences.registerOnSharedPreferenceChangeListener(sharedPreferenceListener);

            if (mCustomPreferences != null) {
                mCustomPreferences.registerOnSharedPreferenceChangeListener(
                        sharedPreferenceListener);
            }
            Log.v(TAG, "listeners: " + mListeners);
        }
    }

    /**
     * Remove a specific SettingsListener. This should be done in onPause if a
     * listener has been set.
     */
    public void removeListener(OnSettingChangedListener listener) {
        synchronized (mLock) {
            if (listener == null) {
                throw new IllegalArgumentException();
            }

            if (!mListeners.contains(listener)) {
                return;
            }

            int index = mListeners.indexOf(listener);
            mListeners.remove(listener);

            OnSharedPreferenceChangeListener sharedPreferenceListener =
                    mSharedPreferenceListeners.get(index);
            mSharedPreferenceListeners.remove(index);
            mDefaultPreferences.unregisterOnSharedPreferenceChangeListener(
                    sharedPreferenceListener);

            if (mCustomPreferences != null) {
                mCustomPreferences.unregisterOnSharedPreferenceChangeListener(
                        sharedPreferenceListener);
            }
        }
    }

    /**
     * Remove all OnSharedPreferenceChangedListener's. This should be done in
     * onDestroy.
     */
    public void removeAllListeners() {
        synchronized (mLock) {
            for (OnSharedPreferenceChangeListener listener : mSharedPreferenceListeners) {
                mDefaultPreferences.unregisterOnSharedPreferenceChangeListener(listener);

                if (mCustomPreferences != null) {
                    mCustomPreferences.unregisterOnSharedPreferenceChangeListener(listener);
                }
            }
            mSharedPreferenceListeners.clear();
            mListeners.clear();
        }
    }

    /** This scope stores and retrieves settings from
        default preferences. */
    public static final String SCOPE_GLOBAL = "default_scope";

    /**
     * Returns the SharedPreferences file matching the scope
     * argument.
     *
     * Camera and module preferences files are cached,
     * until the camera id or module id changes, then the listeners
     * are unregistered and a new file is opened.
     */
    private SharedPreferences getPreferencesFromScope(String scope) {
        synchronized (mLock) {
            if (scope.equals(SCOPE_GLOBAL)) {
                return mDefaultPreferences;
            }

            if (mCustomPreferences != null) {
                closePreferences(mCustomPreferences);
            }
            mCustomPreferences = openPreferences(scope);
            return mCustomPreferences;
        }
    }

    /**
     * Set default and valid values for a setting, for a String default and
     * a set of String possible values that are already defined.
     * This is not required.
     */
    public void setDefaults(String key, String defaultValue, String[] possibleValues) {
        synchronized (mLock) {
            mDefaultsStore.storeDefaults(key, defaultValue, possibleValues);
        }
    }

    /**
     * Set default and valid values for a setting, for an Integer default and
     * a set of Integer possible values that are already defined.
     * This is not required.
     */
    public void setDefaults(String key, int defaultValue, int[] possibleValues) {
        synchronized (mLock) {
            String defaultValueString = Integer.toString(defaultValue);
            String[] possibleValuesString = new String[possibleValues.length];
            for (int i = 0; i < possibleValues.length; i++) {
                possibleValuesString[i] = Integer.toString(possibleValues[i]);
            }
            mDefaultsStore.storeDefaults(key, defaultValueString, possibleValuesString);
        }
    }

    /**
     * Set default and valid values for a setting, for a boolean default.
     * The set of boolean possible values is always { false, true }.
     * This is not required.
     */
    public void setDefaults(String key, boolean defaultValue) {
        synchronized (mLock) {
            String defaultValueString = defaultValue ? "1" : "0";
            String[] possibleValues = {"0", "1"};
            mDefaultsStore.storeDefaults(key, defaultValueString, possibleValues);
        }
    }

    /**
     * Retrieve a default from the DefaultsStore as a String.
     */
    public String getStringDefault(String key) {
        synchronized (mLock) {
            return mDefaultsStore.getDefaultValue(key);
        }
    }

    /**
     * Retrieve a default from the DefaultsStore as an Integer.
     */
    public Integer getIntegerDefault(String key) {
        synchronized (mLock) {
            String defaultValueString = mDefaultsStore.getDefaultValue(key);
            return defaultValueString == null ? 0 : Integer.parseInt(defaultValueString);
        }
    }

    /**
     * Retrieve a default from the DefaultsStore as a boolean.
     */
    public boolean getBooleanDefault(String key) {
        synchronized (mLock) {
            String defaultValueString = mDefaultsStore.getDefaultValue(key);
            return defaultValueString == null ? false :
                    (Integer.parseInt(defaultValueString) != 0);
        }
    }

    /**
     * Retrieve a setting's value as a String, manually specifiying
     * a default value.
     */
    public String getString(String scope, String key, String defaultValue) {
        synchronized (mLock) {
            SharedPreferences preferences = getPreferencesFromScope(scope);
            try {
                return preferences.getString(key, defaultValue);
            } catch (ClassCastException e) {
                Log.w(TAG, "existing preference with invalid type, removing and returning default", e);
                preferences.edit().remove(key).apply();
                return defaultValue;
            }
        }
    }

    /**
     * Retrieve a setting's value as a String, using the default value
     * stored in the DefaultsStore.
     */
    @Nullable
    public String getString(String scope, String key) {
        synchronized (mLock) {
            return getString(scope, key, getStringDefault(key));
        }
    }

    /**
     * Retrieve a setting's value as an Integer, manually specifying
     * a default value.
     */
    public int getInteger(String scope, String key, Integer defaultValue) {
        synchronized (mLock) {
            String defaultValueString = Integer.toString(defaultValue);
            String value = getString(scope, key, defaultValueString);
            return convertToInt(value);
        }
    }

    /**
     * Retrieve a setting's value as an Integer, converting the default value
     * stored in the DefaultsStore.
     */
    public int getInteger(String scope, String key) {
        synchronized (mLock) {
            return getInteger(scope, key, getIntegerDefault(key));
        }
    }

    /**
     * Retrieve a setting's value as a boolean, manually specifiying
     * a default value.
     */
    public boolean getBoolean(String scope, String key, boolean defaultValue) {
        synchronized (mLock) {
            String defaultValueString = defaultValue ? "1" : "0";
            String value = getString(scope, key, defaultValueString);
            return convertToBoolean(value);
        }
    }

    /**
     * Retrieve a setting's value as a boolean, converting the default value
     * stored in the DefaultsStore.
     */
    public boolean getBoolean(String scope, String key) {
        synchronized (mLock) {
            return getBoolean(scope, key, getBooleanDefault(key));
        }
    }

    /**
     * If possible values are stored for this key, return the
     * index into that list of the currently set value.
     *
     * For example, if a set of possible values is [2,3,5],
     * and the current value set of this key is 3, this method
     * returns 1.
     *
     * If possible values are not stored for this key, throw
     * an IllegalArgumentException.
     */
    public int getIndexOfCurrentValue(String scope, String key) {
        synchronized (mLock) {
            String[] possibleValues = mDefaultsStore.getPossibleValues(key);
            if (possibleValues == null || possibleValues.length == 0) {
                throw new IllegalArgumentException(
                        "No possible values for scope=" + scope + " key=" + key);
            }

            String value = getString(scope, key);
            for (int i = 0; i < possibleValues.length; i++) {
                if (value.equals(possibleValues[i])) {
                    return i;
                }
            }
            throw new IllegalStateException("Current value for scope=" + scope + " key="
                    + key + " not in list of possible values");
        }
    }

    /**
     * Store a setting's value using a String value.  No conversion
     * occurs before this value is stored in SharedPreferences.
     */
    public void set(String scope, String key, String value) {
        synchronized (mLock) {
            SharedPreferences preferences = getPreferencesFromScope(scope);
            preferences.edit().putString(key, value).apply();
        }
    }

    /**
     * Store a setting's value using an Integer value.  Type conversion
     * to String occurs before this value is stored in SharedPreferences.
     */
    public void set(String scope, String key, int value) {
        synchronized (mLock) {
            set(scope, key, convert(value));
        }
    }

    /**
     * Store a setting's value using a boolean value.  Type conversion
     * to an Integer and then to a String occurs before this value is
     * stored in SharedPreferences.
     */
    public void set(String scope, String key, boolean value) {
        synchronized (mLock) {
            set(scope, key, convert(value));
        }
    }

    /**
     * Set a setting to the default value stored in the DefaultsStore.
     */
    public void setToDefault(String scope, String key) {
        synchronized (mLock) {
            set(scope, key, getStringDefault(key));
        }
    }

    /**
     * If a set of possible values is defined, set the current value
     * of a setting to the possible value found at the given index.
     *
     * For example, if the possible values for a key are [2,3,5],
     * and the index given to this method is 2, then this method would
     * store the value 5 in SharedPreferences for the key.
     *
     * If the index is out of the bounds of the range of possible values,
     * or there are no possible values for this key, then this
     * method throws an exception.
     */
    public void setValueByIndex(String scope, String key, int index) {
        synchronized (mLock) {
            String[] possibleValues = mDefaultsStore.getPossibleValues(key);
            if (possibleValues.length == 0) {
                throw new IllegalArgumentException(
                        "No possible values for scope=" + scope + " key=" + key);
            }

            if (index >= 0 && index < possibleValues.length) {
                set(scope, key, possibleValues[index]);
            } else {
                throw new IndexOutOfBoundsException("For possible values of scope=" + scope
                        + " key=" + key);
            }
        }
    }

    /**
     * Check that a setting has some value stored.
     */
    public boolean isSet(String scope, String key) {
        synchronized (mLock) {
            SharedPreferences preferences = getPreferencesFromScope(scope);
            return preferences.contains(key);
        }
    }

    /**
     * Check whether a settings's value is currently set to the
     * default value.
     */
    public boolean isDefault(String scope, String key) {
        synchronized (mLock) {
            String defaultValue = getStringDefault(key);
            String value = getString(scope, key);
            return value == null ? false : value.equals(defaultValue);
        }
    }

    /**
     * Remove a setting.
     */
    public void remove(String scope, String key) {
        synchronized (mLock) {
            SharedPreferences preferences = getPreferencesFromScope(scope);
            preferences.edit().remove(key).apply();
        }
    }

    /**
     * Package private conversion method to turn ints into preferred
     * String storage format.
     *
     * @param value int to be stored in Settings
     * @return String which represents the int
     */
    static String convert(int value) {
        return Integer.toString(value);
    }

    /**
     * Package private conversion method to turn String storage format into
     * ints.
     *
     * @param value String to be converted to int
     * @return int value of stored String
     */
    static int convertToInt(String value) {
        return Integer.parseInt(value);
    }

    /**
     * Package private conversion method to turn String storage format into
     * booleans.
     *
     * @param value String to be converted to boolean
     * @return boolean value of stored String
     */
    static boolean convertToBoolean(String value) {
        return Integer.parseInt(value) != 0;
    }


    /**
     * Package private conversion method to turn booleans into preferred
     * String storage format.
     *
     * @param value boolean to be stored in Settings
     * @return String which represents the boolean
     */
    static String convert(boolean value) {
        return value ? "1" : "0";
    }
}
