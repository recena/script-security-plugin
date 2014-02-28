/*
 * The MIT License
 *
 * Copyright 2014 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.scriptsecurity.sandbox;

import javax.annotation.CheckForNull;
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.GroovySandbox;

/**
 * Thrown when access to a language element was not permitted.
 * @see GroovySandbox#runInSandbox(Runnable, Whitelist)
 */
public final class RejectedAccessException extends SecurityException {

    private final String signature;

    /**
     * Rejects access to a well-described script element.
     * @param type e.g. {@code field}
     * @param details e.g. {@code some.Class fieldName}
     */
    public RejectedAccessException(String type, String details) {
        super("Scripts not permitted to use " + type + ": " + details);
        signature = type + " " + details;
    }

    /**
     * Rejects access to something which the current {@link StaticWhitelist} format could not describe.
     * @param message a descriptive message in no particular format
     */
    public RejectedAccessException(String message) {
        super(message);
        signature = null;
    }

    /**
     * Gets the signature of the member to which access was rejected.
     * @return a line in the format understood by {@link StaticWhitelist}, or null in case something was rejected for which a known exemption is not available
     */
    public @CheckForNull String getSignature() {
        return signature;
    }

}