package com.mickey.phonemirror;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

public class InputInjector {

    private static Object inputManagerInstance;
    private static Method injectInputEventMethod;
    private static long downTime = 0;

    public static void main(String[] args) {
        System.out.println("InputInjector started. Waiting for inputs on stdin...");
        
        try {
            initializeReflection();
        } catch (Exception e) {
            System.err.println("FATAL: Failed to initialize InputManager reflection: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    processLine(line.trim());
                } catch (Exception e) {
                    System.err.println("Error processing input line '" + line + "': " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("Stdin reader error: " + e.getMessage());
        }
        
        System.out.println("InputInjector exiting.");
    }

    private static void initializeReflection() throws Exception {
        // Strategy 1: Use ServiceManager to get IInputManager binder (scrcpy approach)
        // This works on all Android versions and doesn't depend on hidden getInstance()
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String.class);
            Object inputBinder = getServiceMethod.invoke(null, "input");

            if (inputBinder != null) {
                // Try IInputManager.Stub.asInterface(binder)
                Class<?> iInputManagerStub = Class.forName("android.hardware.input.IInputManager$Stub");
                Method asInterfaceMethod = iInputManagerStub.getDeclaredMethod("asInterface", android.os.IBinder.class);
                inputManagerInstance = asInterfaceMethod.invoke(null, inputBinder);

                // Look for injectInputEvent method on the IInputManager interface
                Class<?> iInputManagerClass = Class.forName("android.hardware.input.IInputManager");
                injectInputEventMethod = iInputManagerClass.getDeclaredMethod("injectInputEvent",
                        InputEvent.class, int.class);
                injectInputEventMethod.setAccessible(true);

                System.out.println("InputManager initialized via ServiceManager (IInputManager binder).");
                return;
            }
        } catch (Exception e) {
            System.err.println("ServiceManager approach failed: " + e.getMessage() + " — trying fallback...");
        }

        // Strategy 2: Fallback to InputManager.getInstance() reflection
        try {
            Class<?> inputManagerClass = Class.forName("android.hardware.input.InputManager");
            Method getInstanceMethod = inputManagerClass.getDeclaredMethod("getInstance");
            getInstanceMethod.setAccessible(true);
            inputManagerInstance = getInstanceMethod.invoke(null);

            injectInputEventMethod = inputManagerClass.getDeclaredMethod("injectInputEvent",
                    InputEvent.class, int.class);
            injectInputEventMethod.setAccessible(true);

            System.out.println("InputManager initialized via getInstance() reflection.");
            return;
        } catch (Exception e) {
            System.err.println("getInstance() fallback also failed: " + e.getMessage());
        }

        throw new RuntimeException("All InputManager initialization strategies failed. " +
                "Ensure this is run via 'adb shell app_process' with shell (UID 2000) privileges.");
    }

    private static void processLine(String line) throws Exception {
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }

        String[] parts = line.split("\\s+");
        String cmd = parts[0].toUpperCase();

        if (cmd.equals("DOWN") || cmd.equals("MOVE") || cmd.equals("UP")) {
            if (parts.length < 4) {
                System.err.println("Invalid touch command format. Expected: <DOWN|MOVE|UP> <pointer_id> <x> <y>");
                return;
            }
            int pointerId = Integer.parseInt(parts[1]);
            float x = Float.parseFloat(parts[2]);
            float y = Float.parseFloat(parts[3]);

            int action;
            long eventTime = SystemClock.uptimeMillis();

            if (cmd.equals("DOWN")) {
                action = MotionEvent.ACTION_DOWN;
                downTime = eventTime;
            } else if (cmd.equals("MOVE")) {
                action = MotionEvent.ACTION_MOVE;
            } else {
                action = MotionEvent.ACTION_UP;
            }

            // Create Touch MotionEvent
            MotionEvent motionEvent = MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                x,
                y,
                0 // metaState
            );
            motionEvent.setSource(InputDevice.SOURCE_TOUCHSCREEN);

            // Inject (0 represents INJECT_INPUT_EVENT_MODE_ASYNC)
            injectInputEventMethod.invoke(inputManagerInstance, motionEvent, 0);
            motionEvent.recycle();

        } else if (cmd.equals("KEY_DOWN") || cmd.equals("KEY_UP")) {
            if (parts.length < 2) {
                System.err.println("Invalid key command format. Expected: <KEY_DOWN|KEY_UP> <keycode>");
                return;
            }
            int keycode = Integer.parseInt(parts[1]);
            int action = cmd.equals("KEY_DOWN") ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;

            long eventTime = SystemClock.uptimeMillis();
            KeyEvent keyEvent = new KeyEvent(
                eventTime,
                eventTime,
                action,
                keycode,
                0, // repeat
                0, // metaState
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0, // scancode
                0  // flags
            );
            keyEvent.setSource(InputDevice.SOURCE_KEYBOARD);

            injectInputEventMethod.invoke(inputManagerInstance, keyEvent, 0);
        } else {
            System.err.println("Unknown command: " + cmd);
        }
    }
}
