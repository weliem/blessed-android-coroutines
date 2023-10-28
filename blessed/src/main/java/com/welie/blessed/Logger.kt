/*
 *   Copyright (c) 2023 Martijn van Welie
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 *
 */
package com.welie.blessed

import android.util.Log
import timber.log.Timber

internal object Logger {
    var enabled = true

    /**
     * Send a verbose log message.
     *
     * @param tag Used to identify the source of a log message.  It usually identifies
     * the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    fun v(tag: String, msg: String) {
        triggerLogger(Log.VERBOSE, tag, msg)
    }

    /** Log an verbose message with optional format args.  */
    fun v(tag: String, msg: String, vararg args: Any) {
        triggerLogger(Log.VERBOSE, tag, msg, *args)
    }

    /**
     * Send a debug log message.
     *
     * @param tag Used to identify the source of a log message.  It usually identifies
     * the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    fun d(tag: String, msg: String) {
        triggerLogger(Log.DEBUG, tag, msg)
    }

    /** Log an debug message with optional format args.  */
    fun d(tag: String, msg: String, vararg args: Any) {
        triggerLogger(Log.DEBUG, tag, msg, *args)
    }

    /**
     * Send an info log message.
     *
     * @param tag Used to identify the source of a log message.  It usually identifies
     * the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    fun i(tag: String, msg: String) {
        triggerLogger(Log.INFO, tag, msg)
    }

    /** Log an info message with optional format args.  */
    fun i(tag: String, msg: String, vararg args: Any) {
        triggerLogger(Log.INFO, tag, msg, *args)
    }

    /**
     * Send a warn log message.
     *
     * @param tag Used to identify the source of a log message.  It usually identifies
     * the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    fun w(tag: String, msg: String) {
        triggerLogger(Log.WARN, tag, msg)
    }

    /** Log an warn message with optional format args.  */
    fun w(tag: String, msg: String, vararg args: Any) {
        triggerLogger(Log.WARN, tag, msg, *args)
    }

    /**
     * Send an error log message.
     *
     * @param tag Used to identify the source of a log message.  It usually identifies
     * the class or activity where the log call occurs.
     * @param msg The message you would like logged.
     */
    fun e(tag: String, msg: String) {
        triggerLogger(Log.ERROR, tag, msg)
    }

    /** Log an error message with optional format args.  */
    fun e(tag: String, msg: String, vararg args: Any) {
        triggerLogger(Log.ERROR, tag, msg, *args)
    }

    /**
     * What a Terrible Failure: Report a condition that should never happen.
     * The error will always be logged at level severe with the call stack.
     * @param tag Used to identify the source of a log message.
     * @param msg The message you would like logged.
     */
    fun wtf(tag: String, msg: String) {
        triggerLogger(Log.ASSERT, tag, msg)
    }

    /** Log an wtf message with optional format args.  */
    fun wtf(tag: String, msg: String, vararg args: Any) {
        triggerLogger(Log.ASSERT, tag, msg, *args)
    }

    private fun triggerLogger(priority: Int, tag: String, msg: String, vararg args: Any) {
        if (enabled) {
            triggerLogger(priority, tag, String.format(msg, *args))
        }
    }

    private fun triggerLogger(priority: Int, tag: String, msg: String) {
        if (enabled) {
            Timber.tag(tag).log(priority, msg)
        }
    }
}