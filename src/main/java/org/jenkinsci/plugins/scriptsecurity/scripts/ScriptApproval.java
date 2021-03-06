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

package org.jenkinsci.plugins.scriptsecurity.scripts;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.AclAwareWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.ProxyWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.StaticWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.Whitelist;
import hudson.Extension;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.RootAction;
import hudson.model.Saveable;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.XStream2;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import net.sf.json.JSON;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.bind.JavaScriptMethod;

/**
 * Manages approved scripts.
 */
@Extension public class ScriptApproval implements RootAction, Saveable {

    private static final Logger LOG = Logger.getLogger(ScriptApproval.class.getName());

    private static final XStream2 XSTREAM2 = new XStream2();
    static {
        // Compatibility:
        XSTREAM2.alias("com.cloudbees.hudson.plugins.modeling.scripts.ScriptApproval", ScriptApproval.class);
        XSTREAM2.alias("com.cloudbees.hudson.plugins.modeling.scripts.ScriptApproval$PendingScript", PendingScript.class);
        XSTREAM2.alias("com.cloudbees.hudson.plugins.modeling.scripts.ScriptApproval$PendingSignature", PendingSignature.class);
        // Current:
        XSTREAM2.alias("scriptApproval", ScriptApproval.class);
        XSTREAM2.alias("approvedClasspathEntry", ApprovedClasspathEntry.class);
        XSTREAM2.alias("pendingScript", PendingScript.class);
        XSTREAM2.alias("pendingSignature", PendingSignature.class);
        XSTREAM2.alias("pendingClasspathEntry", PendingClasspathEntry.class);
    }

    /** Gets the singleton instance. */
    public static @Nonnull ScriptApproval get() {
        final Jenkins jenkins = getActiveJenkinsInstance();
        ScriptApproval instance = jenkins.getExtensionList(RootAction.class).get(ScriptApproval.class);
        if (instance == null) {
            throw new IllegalStateException("maybe need to rebuild plugin?");
        }
        return instance;
    }

    /**
     * Approved classpath entry.
     * 
     * It is keyed only by the hash,
     * but additional information is provided for convenience.
     */
    @Restricted(NoExternalUse.class) // for use from Jelly and tests
    public static class ApprovedClasspathEntry implements Comparable<ApprovedClasspathEntry> {
        private final String hash;
        private final URL url;
        
        public ApprovedClasspathEntry(String hash, URL url) {
            this.hash = hash;
            this.url = url;
        }
        
        public String getHash() {
            return hash;
        }
        
        public URL getURL() {
            return url;
        }
        @Override public int hashCode() {
            return hash.hashCode();
        }
        @Override public boolean equals(Object obj) {
            return obj instanceof ApprovedClasspathEntry && ((ApprovedClasspathEntry) obj).hash.equals(hash);
        }
        @Override public int compareTo(ApprovedClasspathEntry o) {
            return hash.compareTo(o.hash);
        }
    }
    
    /** All scripts which are already approved, via {@link #hash}. */
    private final TreeSet<String> approvedScriptHashes = new TreeSet<String>();

    /** All sandbox signatures which are already whitelisted, in {@link StaticWhitelist} format. */
    private final TreeSet<String> approvedSignatures = new TreeSet<String>();

    /** All sandbox signatures which are already whitelisted for ACL-only use, in {@link StaticWhitelist} format. */
    private /*final*/ TreeSet<String> aclApprovedSignatures;

    /** All external classpath entries allowed used for scripts. */
    private /*final*/ TreeSet<ApprovedClasspathEntry> approvedClasspathEntries;

    /* for test */ void addApprovedClasspathEntry(ApprovedClasspathEntry acp) {
        approvedClasspathEntries.add(acp);
    }

    @Restricted(NoExternalUse.class) // for use from Jelly
    public static abstract class PendingThing {

        /** @deprecated only used from historical records */
        @Deprecated private String user;
        
        private @Nonnull ApprovalContext context;

        PendingThing(@Nonnull ApprovalContext context) {
            this.context = context;
        }

        public @Nonnull ApprovalContext getContext() {
            return context;
        }

        private Object readResolve() {
            if (user != null) {
                context = ApprovalContext.create().withUser(user);
                user = null;
            }
            return this;
        }

    }

    @Restricted(NoExternalUse.class) // for use from Jelly
    public static final class PendingScript extends PendingThing {
        public final String script;
        private final String language;
        PendingScript(@Nonnull String script, @Nonnull Language language, @Nonnull ApprovalContext context) {
            super(context);
            this.script = script;
            this.language = language.getName();
        }
        public String getHash() {
            return hash(script, language);
        }
        public Language getLanguage() {
            final Jenkins jenkins = getActiveJenkinsInstance();
            for (Language l : jenkins.getExtensionList(Language.class)) {
                if (l.getName().equals(language)) {
                    return l;
                }
            }
            return new Language() {
                @Override public String getName() {
                    return language;
                }
                @Override public String getDisplayName() {
                    return "<missing language: " + language + ">";
                }
            };
        }
        @Override public int hashCode() {
            return script.hashCode() ^ language.hashCode();
        }
        @Override public boolean equals(Object obj) {
            // Intentionally do not consider context in equality check.
            return obj instanceof PendingScript && ((PendingScript) obj).language.equals(language) && ((PendingScript) obj).script.equals(script);
        }
    }

    @Restricted(NoExternalUse.class) // for use from Jelly
    public static final class PendingSignature extends PendingThing {
        public final String signature;
        public final boolean dangerous;
        PendingSignature(@Nonnull String signature, boolean dangerous, @Nonnull ApprovalContext context) {
            super(context);
            this.signature = signature;
            this.dangerous = dangerous;
        }
        public String getHash() {
            // Security important, just for UI:
            return Integer.toHexString(hashCode());
        }
        @Override public int hashCode() {
            return signature.hashCode();
        }
        @Override public boolean equals(Object obj) {
            return obj instanceof PendingSignature && ((PendingSignature) obj).signature.equals(signature);
        }
    }

    /**
     * A classpath entry requiring approval by an administrator.
     * 
     * They are distinguished only with hashes,
     * but other additional information is provided for possible administrator use.
     * (Currently no context information is actually displayed, since the entry could be used from many scripts, so this might be misleading.)
     */
    @Restricted(NoExternalUse.class) // for use from Jelly
    public static final class PendingClasspathEntry extends PendingThing implements Comparable<PendingClasspathEntry> {
        private final String hash;
        private final URL url;
        
        private static final ApprovalContext SEARCH_APPROVAL_CONTEXT = ApprovalContext.create();
        private static URL SEARCH_APPROVAL_URL;
        
        static {
            try {
                SEARCH_APPROVAL_URL = new URL("http://invalid.url/do/not/use");
            } catch (Throwable e) {
                // Should not happen
                LOG.log(Level.WARNING, "Unexpected exception", e);
            }
        }
        
        PendingClasspathEntry(@Nonnull String hash, @Nonnull URL url, @Nonnull ApprovalContext context) {
            super(context);
            /**
             * hash should be stored as files located at the classpath can be modified.
             */
            this.hash = hash;
            this.url = url;
        }
        
        public @Nonnull String getHash() {
            return hash;
        }
        
        public @Nonnull URL getURL() {
            return url;
        }
        @Override public int hashCode() {
            return getHash().hashCode();
        }
        @Override public boolean equals(Object obj) {
            return obj instanceof PendingClasspathEntry && ((PendingClasspathEntry) obj).getHash().equals(getHash());
        }
        @Override public int compareTo(PendingClasspathEntry o) {
            return hash.compareTo(o.hash);
        }
        
        public static @Nonnull PendingClasspathEntry searchKeyFor(@Nonnull String hash) {
            final PendingClasspathEntry entry = new PendingClasspathEntry(hash, 
                    SEARCH_APPROVAL_URL, SEARCH_APPROVAL_CONTEXT);
            return entry;
        }
    }

    private final LinkedHashSet<PendingScript> pendingScripts = new LinkedHashSet<PendingScript>();

    private final LinkedHashSet<PendingSignature> pendingSignatures = new LinkedHashSet<PendingSignature>();

    private /*final*/ TreeSet<PendingClasspathEntry> pendingClasspathEntries;

    @CheckForNull
    private PendingClasspathEntry getPendingClasspathEntry(@Nonnull String hash) {
        PendingClasspathEntry e = pendingClasspathEntries.floor(PendingClasspathEntry.searchKeyFor(hash));
        if (e != null && e.hash.equals(hash)) {
            return e;
        } else {
            return null;
        }
    }

    /* for test */ void addPendingClasspathEntry(PendingClasspathEntry pcp) {
        pendingClasspathEntries.add(pcp);
    }

    public ScriptApproval() {
        try {
            load();
        } catch (IOException x) {
            LOG.log(Level.WARNING, null, x);
        }
        /* can be null when upgraded from old versions.*/
        if (aclApprovedSignatures == null) {
            aclApprovedSignatures = new TreeSet<String>();
        }
        if (approvedClasspathEntries == null) {
            approvedClasspathEntries = new TreeSet<ApprovedClasspathEntry>();
        }
        if (pendingClasspathEntries == null) {
            pendingClasspathEntries = new TreeSet<PendingClasspathEntry>();
        }
    }

    private static String hash(String script, String language) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(language.getBytes("UTF-8"));
            digest.update((byte) ':');
            digest.update(script.getBytes("UTF-8"));
            return Util.toHexString(digest.digest());
        } catch (NoSuchAlgorithmException x) {
            throw new AssertionError(x);
        } catch (UnsupportedEncodingException x) {
            throw new AssertionError(x);
        }
    }

    /** Creates digest of JAR contents. */
    private static String hashClasspathEntry(URL entry) throws IOException {
        InputStream is = entry.openStream();
        try {
            DigestInputStream input = null;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                input = new DigestInputStream(new BufferedInputStream(is), digest);
                byte[] buffer = new byte[1024];
                while (input.read(buffer) != -1) {
                    // discard
                }
                return Util.toHexString(digest.digest());
            } catch (NoSuchAlgorithmException x) {
                throw new AssertionError(x);
            } finally {
                if (input != null) {
                    input.close();
                }
            }
        } finally {
            is.close();
        }
    }

    /**
     * Used when someone is configuring a script.
     * Typically you would call this from a {@link DataBoundConstructor}.
     * It should also be called from a {@code readResolve} method (which may then simply return {@code this}),
     * so that administrators can for example POST to {@code config.xml} and have their scripts be considered approved.
     * <p>If the script has already been approved, this does nothing.
     * Otherwise, if this user has the {@link Jenkins#RUN_SCRIPTS} permission (and is not {@link ACL#SYSTEM}), or Jenkins is running without security, it is added to the approved list.
     * Otherwise, it is added to the pending list.
     * @param script the text of a possibly novel script
     * @param language the language in which it is written
     * @param context any additional information about how where or by whom this is being configured
     * @return {@code script}, for convenience
     * @throws IllegalStateException {@link Jenkins} instance is not ready
     */
    public synchronized String configuring(@Nonnull String script, @Nonnull Language language, @Nonnull ApprovalContext context) {
        final String hash = hash(script, language.getName());
        final Jenkins jenkins = getActiveJenkinsInstance();
        if (!approvedScriptHashes.contains(hash)) {
            if (!jenkins.isUseSecurity() || Jenkins.getAuthentication() != ACL.SYSTEM && jenkins.hasPermission(Jenkins.RUN_SCRIPTS)) {
                approvedScriptHashes.add(hash);
            } else {
                String key = context.getKey();
                if (key != null) {
                    Iterator<PendingScript> it = pendingScripts.iterator();
                    while (it.hasNext()) {
                        if (key.equals(it.next().getContext().getKey())) {
                            it.remove();
                        }
                    }
                }
                pendingScripts.add(new PendingScript(script, language, context));
            }
            try {
                save();
            } catch (IOException x) {
                LOG.log(Level.WARNING, null, x);
            }
        }
        return script;
    }

    /**
     * Called when a script is about to be used (evaluated).
     * @param script a possibly unapproved script
     * @param language the language in which it is written
     * @return {@code script}, for convenience
     * @throws UnapprovedUsageException in case it has not yet been approved
     */
    public synchronized String using(@Nonnull String script, @Nonnull Language language) throws UnapprovedUsageException {
        if (script.length() == 0) {
            // As a special case, always consider the empty script preapproved, as this is usually the default for new fields,
            // and in many cases there is some sensible behavior for an emoty script which we want to permit.
            return script;
        }
        String hash = hash(script, language.getName());
        if (!approvedScriptHashes.contains(hash)) {
            // Probably need not add to pendingScripts, since generally that would have happened already in configuring.
            throw new UnapprovedUsageException(hash);
        }
        return script;
    }

    /**
     * Called when configuring a classpath entry.
     * Usage is similar to {@link #configuring(String, Language, ApprovalContext)}.
     * @param entry entry to be configured
     * @param context any additional information
     * @throws IllegalStateException {@link Jenkins} instance is not ready
     */
    public synchronized void configuring(@Nonnull ClasspathEntry entry, @Nonnull ApprovalContext context) {
        //TODO: better error propagation
        final Jenkins jenkins = getActiveJenkinsInstance();
        URL url = entry.getURL();
        String hash;
        try {
            hash = hashClasspathEntry(url);
        } catch (IOException x) {
            // This is a case the path doesn't really exist
            LOG.log(Level.WARNING, null, x);
            return;
        }
        
        ApprovedClasspathEntry acp = new ApprovedClasspathEntry(hash, url);
        if (!approvedClasspathEntries.contains(acp)) {
            boolean shouldSave = false;
            PendingClasspathEntry pcp = new PendingClasspathEntry(hash, url, context);
            if (!jenkins.isUseSecurity() || (Jenkins.getAuthentication() != ACL.SYSTEM && jenkins.hasPermission(Jenkins.RUN_SCRIPTS))) {
                LOG.log(Level.FINE, "Classpath entry {0} ({1}) is approved as configured with RUN_SCRIPTS permission.", new Object[] {url, hash});
                pendingClasspathEntries.remove(pcp);
                approvedClasspathEntries.add(acp);
                shouldSave = true;
            } else {
                if (pendingClasspathEntries.add(pcp)) {
                    LOG.log(Level.FINE, "{0} ({1}) is pending", new Object[] {url, hash});
                    shouldSave = true;
                }
            }
            if (shouldSave) {
                try {
                    save();
                } catch (IOException x) {
                    LOG.log(Level.WARNING, null, x);
                }
            }
        }
    }
    
    /**
     * Like {@link #checking(String, Language)} but for classpath entries.
     * (This is automatic if use {@link ClasspathEntry} as a configuration element.)
     * @param entry the classpath entry to verify
     * @return whether it will be approved
     * @throws IllegalStateException {@link Jenkins} instance is not ready
     */
    public synchronized FormValidation checking(@Nonnull ClasspathEntry entry) {
        //TODO: better error propagation
        final Jenkins jenkins = getActiveJenkinsInstance();
        URL url = entry.getURL();
        try {
            if (!jenkins.hasPermission(Jenkins.RUN_SCRIPTS) && !approvedClasspathEntries.contains(new ApprovedClasspathEntry(hashClasspathEntry(url), url))) {
                return FormValidation.error(Messages.ClasspathEntry_path_notApproved());
            } else {
                return FormValidation.ok();
            }
        } catch (FileNotFoundException x) {
            return FormValidation.error(Messages.ClasspathEntry_path_notExists());
        } catch (IOException x) {
            return FormValidation.error(x, "Could not verify: " + url); // TODO NO18N
        }
    }
    
    /**
     * Asserts that a classpath entry is approved.
     * Also records it as a pending entry if not approved.
     * @param entry a classpath entry
     * @throws IOException when failed to the entry is inaccessible
     * @throws UnapprovedClasspathException when the entry is not approved
     */
    public synchronized void using(@Nonnull ClasspathEntry entry) throws IOException, UnapprovedClasspathException {
        URL url = entry.getURL();
        String hash = hashClasspathEntry(url);
        
        if (!approvedClasspathEntries.contains(new ApprovedClasspathEntry(hash, url))) {
            // Never approve classpath here.
            ApprovalContext context = ApprovalContext.create();
            if (pendingClasspathEntries.add(new PendingClasspathEntry(hash, url, context))) {
                LOG.log(Level.FINE, "{0} ({1}) is pending.", new Object[] {url, hash});
                try {
                    save();
                } catch (IOException x) {
                    LOG.log(Level.WARNING, null, x);
                }
            }
            throw new UnapprovedClasspathException(url, hash);
        }
        
        LOG.log(Level.FINER, "{0} ({1}) had been approved", new Object[] {url, hash});
    }

    /**
     * To be used from form validation, in a {@code doCheckFieldName} method.
     * @param script a possibly unapproved script
     * @param language the language in which it is written
     * @return a warning in case the script is not yet approved and this user lacks {@link Jenkins#RUN_SCRIPTS}, else {@link FormValidation#ok()}
     */
    public synchronized FormValidation checking(@Nonnull String script, @Nonnull Language language) {
        if (!Jenkins.getInstance().hasPermission(Jenkins.RUN_SCRIPTS) && !approvedScriptHashes.contains(hash(script, language.getName()))) {
            return FormValidation.warningWithMarkup("A Jenkins administrator will need to approve this script before it can be used.");
        } else {
            return FormValidation.ok();
        }
    }

    /**
     * Unconditionally approve a script.
     * Does no access checks and does not automatically save changes to disk.
     * Useful mainly for testing.
     * @param script the text of a possibly novel script
     * @param language the language in which it is written
     * @return {@code script}, for convenience
     */
    public synchronized String preapprove(@Nonnull String script, @Nonnull Language language) {
        approvedScriptHashes.add(hash(script, language.getName()));
        return script;
    }

    /**
     * Unconditionally approves all pending scripts.
     * Does no access checks and does not automatically save changes to disk.
     * Useful mainly for testing in combination with {@code @LocalData}.
     */
    public synchronized void preapproveAll() {
        for (PendingScript ps : pendingScripts) {
            approvedScriptHashes.add(ps.getHash());
        }
        pendingScripts.clear();
    }

    /**
     * To be called when a sandbox rejects access for a script not using manual approval.
     * The signature of the failing method (if known) will be added to the pending list.
     * @param x an exception with the details
     * @param context any additional information about where or by whom this script was run
     * @return {@code x}, for convenience in rethrowing
     */
    public synchronized RejectedAccessException accessRejected(@Nonnull RejectedAccessException x, @Nonnull ApprovalContext context) {
        String signature = x.getSignature();
        if (signature != null && pendingSignatures.add(new PendingSignature(signature, x.isDangerous(), context))) {
            try {
                save();
            } catch (IOException x2) {
                LOG.log(Level.WARNING, null, x2);
            }
        }
        return x;
    }

    @Restricted(NoExternalUse.class) // Jelly, implementation
    public synchronized String[] getApprovedSignatures() {
        return approvedSignatures.toArray(new String[approvedSignatures.size()]);
    }

    @Restricted(NoExternalUse.class) // Jelly, implementation
    public synchronized String[] getAclApprovedSignatures() {
        return aclApprovedSignatures.toArray(new String[aclApprovedSignatures.size()]);
    }

    @Restricted(NoExternalUse.class) // implementation
    @Extension public static final class ApprovedWhitelist extends ProxyWhitelist {
        public ApprovedWhitelist() throws IOException {
            reconfigure();
        }
        String[][] reconfigure() throws IOException {
            ScriptApproval instance = ScriptApproval.get();
            synchronized (instance) {
                reset(Collections.singleton(new AclAwareWhitelist(new StaticWhitelist(instance.approvedSignatures), new StaticWhitelist(instance.aclApprovedSignatures))));
                return new String[][] {instance.getApprovedSignatures(), instance.getAclApprovedSignatures()};
            }
        }
    }

    @Override public String getIconFileName() {
        return null;
    }

    @Override public String getDisplayName() {
        return null;
    }

    @Override public String getUrlName() {
        return "scriptApproval";
    }

    @CheckForNull
    private XmlFile getConfigFile() {
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            return null;
        }
        return new XmlFile(XSTREAM2, new File(jenkins.getRootDir(), getUrlName() + ".xml"));
    }

    private synchronized void load() throws IOException {
        final XmlFile xml = getConfigFile();
        if (xml != null && xml.exists()) {
            xml.unmarshal(this);
        }
    }

    @Override public synchronized void save() throws IOException {
        final XmlFile configFile = getConfigFile();
        if (configFile == null) {
            throw new IOException("Cannot get config file. Probably, Jenkins is not ready");
        }
        configFile.write(this);
        // TBD: outside synch block: SaveableListener.fireOnChange(this, getConfigFile());
    }

    @Restricted(NoExternalUse.class) // for use from Jelly
    public Set<PendingScript> getPendingScripts() {
        return pendingScripts;
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public void approveScript(String hash) throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        synchronized (this) {
            approvedScriptHashes.add(hash);
            removePendingScript(hash);
            save();
        }
        SecurityContext orig = ACL.impersonate(ACL.SYSTEM);
        try {
            for (ApprovalListener listener : jenkins.getExtensionList(ApprovalListener.class)) {
                listener.onApproved(hash);
            }
        } finally {
            SecurityContextHolder.setContext(orig);
        }
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized void denyScript(String hash) throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        approvedScriptHashes.remove(hash);
        removePendingScript(hash);
        save();
    }

    private synchronized void removePendingScript(String hash) {
        Iterator<PendingScript> it = pendingScripts.iterator();
        while (it.hasNext()) {
            if (it.next().getHash().equals(hash)) {
                it.remove();
                break;
            }
        }
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized void clearApprovedScripts() throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        approvedScriptHashes.clear();
        save();
    }

    @Restricted(NoExternalUse.class) // for use from Jelly
    public Set<PendingSignature> getPendingSignatures() {
        return pendingSignatures;
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized String[][] approveSignature(String signature) throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        pendingSignatures.remove(new PendingSignature(signature, false, ApprovalContext.create()));
        approvedSignatures.add(signature);
        save();
        return jenkins.getExtensionList(Whitelist.class).get(ApprovedWhitelist.class).reconfigure();
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized String[][] aclApproveSignature(String signature) throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        pendingSignatures.remove(new PendingSignature(signature, false, ApprovalContext.create()));
        aclApprovedSignatures.add(signature);
        save();
        return jenkins.getExtensionList(Whitelist.class).get(ApprovedWhitelist.class).reconfigure();
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized void denySignature(String signature) throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        pendingSignatures.remove(new PendingSignature(signature, false, ApprovalContext.create()));
        save();
    }

    // TODO nicer would be to allow the user to actually edit the list directly (with syntax checks)
    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod public synchronized String[][] clearApprovedSignatures() throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        approvedSignatures.clear();
        aclApprovedSignatures.clear();
        save();
        // Should be [[], []] but still returning it for consistency with approve methods.
        return jenkins.getExtensionList(Whitelist.class).get(ApprovedWhitelist.class).reconfigure();
    }

    @Restricted(NoExternalUse.class)
    public synchronized List<ApprovedClasspathEntry> getApprovedClasspathEntries() {
        ArrayList<ApprovedClasspathEntry> r = new ArrayList<ApprovedClasspathEntry>(approvedClasspathEntries);
        Collections.sort(r, new Comparator<ApprovedClasspathEntry>() {
            @Override public int compare(ApprovedClasspathEntry o1, ApprovedClasspathEntry o2) {
                return o1.url.toString().compareTo(o2.url.toString());
            }
        });
        return r;
    }

    @Restricted(NoExternalUse.class)
    public synchronized List<PendingClasspathEntry> getPendingClasspathEntries() {
        List<PendingClasspathEntry> r = new ArrayList<PendingClasspathEntry>(pendingClasspathEntries);
        Collections.sort(r, new Comparator<PendingClasspathEntry>() {
            @Override public int compare(PendingClasspathEntry o1, PendingClasspathEntry o2) {
                return o1.url.toString().compareTo(o2.url.toString());
            }
        });
        return r;
    }

    @Restricted(NoExternalUse.class) // for use from Ajax
    @JavaScriptMethod
    public JSON getClasspathRenderInfo() {
        JSONArray pendings = new JSONArray();
        for (PendingClasspathEntry cp : getPendingClasspathEntries()) {
            pendings.add(new JSONObject().element("hash", cp.getHash()).element("path", ClasspathEntry.urlToPath(cp.getURL())));
        }
        JSONArray approveds = new JSONArray();
        for (ApprovedClasspathEntry cp : getApprovedClasspathEntries()) {
            approveds.add(new JSONObject().element("hash", cp.getHash()).element("path", ClasspathEntry.urlToPath(cp.getURL())));
        }
        return new JSONArray().element(pendings).element(approveds);
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod
    public JSON approveClasspathEntry(String hash) throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        URL url = null;
        synchronized (this) {
            final PendingClasspathEntry cp = getPendingClasspathEntry(hash);
            if (cp != null) {
                pendingClasspathEntries.remove(cp);
                url = cp.getURL();
                approvedClasspathEntries.add(new ApprovedClasspathEntry(hash, url));
                save();
            }
        }
        if (url != null) {
            SecurityContext orig = ACL.impersonate(ACL.SYSTEM);
            try {
                for (ApprovalListener listener : jenkins.getExtensionList(ApprovalListener.class)) {
                    listener.onApprovedClasspathEntry(hash, url);
                }
            } finally {
                SecurityContextHolder.setContext(orig);
            }
        }
        return getClasspathRenderInfo();
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod
    public JSON denyClasspathEntry(String hash) throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        PendingClasspathEntry cp = getPendingClasspathEntry(hash);
        if (cp != null) {
            pendingClasspathEntries.remove(cp);
            save();
        }
        return getClasspathRenderInfo();
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod
    public JSON denyApprovedClasspathEntry(String hash) throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        if (approvedClasspathEntries.remove(new ApprovedClasspathEntry(hash, null))) {
            save();
        }
        return getClasspathRenderInfo();
    }

    @Restricted(NoExternalUse.class) // for use from AJAX
    @JavaScriptMethod
    public synchronized JSON clearApprovedClasspathEntries() throws IOException {
        final Jenkins jenkins = getJenkins();
        jenkins.checkPermission(Jenkins.RUN_SCRIPTS);
        approvedClasspathEntries.clear();
        save();
        return getClasspathRenderInfo();
    }

    //TODO: Remove once the baseline supports Jenkins.getActiveInstance (1.590+)
    @Nonnull
    private static Jenkins getJenkins() throws IOException {
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new IOException("Jenkins instance is not ready");
        }
        return jenkins;
    }
    
    @Nonnull
    private static Jenkins getActiveJenkinsInstance() throws IllegalStateException {
        final Jenkins jenkins = Jenkins.getInstance();
        if (jenkins == null) {
            throw new IllegalStateException("Jenkins instance is not ready");
        }
        return jenkins;
    }
}
