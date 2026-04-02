package com.noke.nokemobilelibrary;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Serializes BLE operations to prevent "Connection 104" errors.
 * Similar to iOS Core Bluetooth's automatic operation serialization.
 * 
 * Ensures only one BLE operation executes at a time and waits for
 * its callback (onCharacteristicWrite/Read) before starting the next.
 */
public class BleOperationQueue {
    private static final String TAG = "BleOperationQueue";
    private static final long OPERATION_TIMEOUT_MS = 5000; // 5 seconds timeout
    
    private final Queue<Runnable> operationQueue = new LinkedList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isOperationInProgress = false;
    private Runnable timeoutRunnable = null;
    
    /**
     * Add a BLE operation to the queue.
     * If no operation is in progress, executes immediately.
     * Otherwise, queues it for later execution.
     */
    public void enqueue(Runnable operation) {
        mainHandler.post(() -> {
            operationQueue.add(operation);
            Log.d(TAG, "Enqueued operation. Queue size: " + operationQueue.size());
            executeNextIfIdle();
        });
    }
    
    /**
     * Must be called when a BLE operation completes (from onCharacteristicWrite/Read callback).
     * This signals the queue to start the next operation.
     */
    public void notifyOperationComplete() {
        mainHandler.post(() -> {
            Log.d(TAG, "Operation completed. Remaining queue size: " + operationQueue.size());
            
            // Cancel timeout since operation completed successfully
            if (timeoutRunnable != null) {
                mainHandler.removeCallbacks(timeoutRunnable);
                timeoutRunnable = null;
            }
            
            isOperationInProgress = false;
            
            // Increased delay from 50ms to 200ms - some Android devices need more time
            // between BLE operations to avoid Connection 104 errors
            mainHandler.postDelayed(this::executeNextIfIdle, 200);
        });
    }
    
    private void executeNextIfIdle() {
        if (isOperationInProgress) {
            Log.d(TAG, "Operation already in progress, waiting...");
            return;
        }
        
        Runnable nextOperation = operationQueue.poll();
        if (nextOperation == null) {
            Log.d(TAG, "Queue empty, idle");
            return;
        }
        
        isOperationInProgress = true;
        Log.d(TAG, "Executing next operation. Remaining: " + operationQueue.size());
        
        // Set up timeout in case callback never arrives
        timeoutRunnable = () -> {
            Log.e(TAG, "Operation timeout! No callback received within " + OPERATION_TIMEOUT_MS + "ms");
            Log.e(TAG, "Force-completing operation and moving to next");
            isOperationInProgress = false;
            timeoutRunnable = null;
            executeNextIfIdle();
        };
        mainHandler.postDelayed(timeoutRunnable, OPERATION_TIMEOUT_MS);
        
        try {
            nextOperation.run();
        } catch (Exception e) {
            Log.e(TAG, "Operation failed", e);
            // Continue with next operation even if this one failed
            notifyOperationComplete();
        }
    }
    
    /**
     * Clear all pending operations (e.g., when device disconnects).
     */
    public void clear() {
        mainHandler.post(() -> {
            int size = operationQueue.size();
            operationQueue.clear();
            isOperationInProgress = false;
            
            // Cancel any pending timeout
            if (timeoutRunnable != null) {
                mainHandler.removeCallbacks(timeoutRunnable);
                timeoutRunnable = null;
            }
            
            Log.d(TAG, "Cleared " + size + " pending operations");
        });
    }
    
    /**
     * Get the number of pending operations.
     */
    public int size() {
        return operationQueue.size();
    }
}
