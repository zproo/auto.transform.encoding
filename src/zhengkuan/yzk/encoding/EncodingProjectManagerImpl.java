// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be
// found in the LICENSE file.
package zhengkuan.yzk.encoding;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileTypes.StdFileTypes;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.encoding.EncodingManager;
import com.intellij.openapi.vfs.encoding.EncodingProjectManager;
import com.intellij.openapi.vfs.newvfs.impl.VirtualFileSystemEntry;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashMap;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.beans.PropertyChangeListener;
import java.nio.charset.Charset;
import java.util.*;

@State(name = "Encoding", storages = @Storage("encodings.xml"))
public class EncodingProjectManagerImpl extends EncodingProjectManager implements PersistentStateComponent<Element> {
    @NonNls
    private static final String PROJECT_URL = "PROJECT";

    private final Project myProject;
    private final EncodingManagerImpl myIdeEncodingManager;
    private boolean myNative2AsciiForPropertiesFiles;
    private Charset myDefaultCharsetForPropertiesFiles;
    private final SimpleModificationTracker myModificationTracker = new SimpleModificationTracker();

    private BOMForNewUTF8Files myBOMForNewUTF8Files = BOMForNewUTF8Files.NEVER;

    public EncodingProjectManagerImpl(Project project, EncodingManager ideEncodingManager) {
        myProject = project;
        myIdeEncodingManager = (EncodingManagerImpl)ideEncodingManager;
        StartupManager.getInstance(project).runWhenProjectIsInitialized(this::reloadAlreadyLoadedDocuments);
    }

    private final Map<VirtualFile, Charset> myMapping = ContainerUtil.newConcurrentMap();
    private volatile Charset myProjectCharset;

    @Override
    public Element getState() {
        Element element = new Element("x");
        if (!myMapping.isEmpty()) {
            List<VirtualFile> files = new ArrayList<>(myMapping.keySet());
            ContainerUtil.quickSort(files, Comparator.comparing(VirtualFile::getPath));
            for (VirtualFile file : files) {
                Charset charset = myMapping.get(file);
                Element child = new Element("file");
                element.addContent(child);
                child.setAttribute("url", file.getUrl());
                child.setAttribute("charset", charset.name());
            }
        }
        if (myProjectCharset != null) {
            Element child = new Element("file");
            element.addContent(child);
            child.setAttribute("url", PROJECT_URL);
            child.setAttribute("charset", myProjectCharset.name());
        }

        if (myNative2AsciiForPropertiesFiles) {
            element.setAttribute("native2AsciiForPropertiesFiles", Boolean.toString(true));
        }

        if (myDefaultCharsetForPropertiesFiles != null) {
            element.setAttribute("defaultCharsetForPropertiesFiles", myDefaultCharsetForPropertiesFiles.name());
        }
        return element;
    }

    @Override
    public void loadState(@NotNull Element element) {
        myMapping.clear();
        List<Element> files = element.getChildren("file");
        if (!files.isEmpty()) {
            Map<VirtualFile, Charset> mapping = new THashMap<>();
            for (Element fileElement : files) {
                String url = fileElement.getAttributeValue("url");
                String charsetName = fileElement.getAttributeValue("charset");
                Charset charset = CharsetToolkit.forName(charsetName);
                if (charset == null) {
                    continue;
                }

                if (url.equals(PROJECT_URL)) {
                    myProjectCharset = charset;
                } else {
                    VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
                    if (file != null) {
                        mapping.put(file, charset);
                    }
                }
            }
            myMapping.putAll(mapping);
        }

        myNative2AsciiForPropertiesFiles = Boolean.parseBoolean(
            element.getAttributeValue("native2AsciiForPropertiesFiles"));
        myDefaultCharsetForPropertiesFiles = CharsetToolkit.forName(
            element.getAttributeValue("defaultCharsetForPropertiesFiles"));

        myModificationTracker.incModificationCount();
    }

    private void reloadAlreadyLoadedDocuments() {
        for (VirtualFile file : myMapping.keySet()) {
            Document cachedDocument = FileDocumentManager.getInstance().getCachedDocument(file);
            if (cachedDocument != null) {
                reload(
                    file); // reload document in the right encoding if someone sneaky (you, BreakpointManager)
                // managed to load the document before project opened
            }
        }
    }

    @Override
    @Nullable
    public Charset getEncoding(@Nullable VirtualFile virtualFile, boolean useParentDefaults) {
        VirtualFile parent = virtualFile;
        while (parent != null) {
            Charset charset = myMapping.get(parent);
            if (charset != null || !useParentDefaults) {return charset;}
            parent = parent.getParent();
        }

        return getDefaultCharset();
    }

    @Override
    public void setEncoding(@Nullable final VirtualFile virtualFileOrDir, @Nullable final Charset charset) {
        Charset oldCharset;

        if (virtualFileOrDir == null) {
            oldCharset = myProjectCharset;
            myProjectCharset = charset;
        } else if (charset == null) {
            oldCharset = myMapping.remove(virtualFileOrDir);
        } else {
            oldCharset = myMapping.put(virtualFileOrDir, charset);
        }

        if (!Comparing.equal(oldCharset, charset) || virtualFileOrDir != null && !Comparing.equal(
            virtualFileOrDir.getCharset(), charset)) {
            myModificationTracker.incModificationCount();
            if (virtualFileOrDir != null) {
                virtualFileOrDir.setCharset(virtualFileOrDir.getBOM() == null ? charset : null);
            }
            reloadAllFilesUnder(virtualFileOrDir);
        }
    }

    private static void reload(@NotNull final VirtualFile virtualFile) {
        ApplicationManager.getApplication().runWriteAction(() -> {
            FileDocumentManager documentManager = FileDocumentManager.getInstance();
            ((VirtualFileListener)documentManager)
                .contentsChanged(
                    new VirtualFileEvent(null, virtualFile, virtualFile.getName(), virtualFile.getParent()));
        });
    }

    @Override
    @NotNull
    public Collection<Charset> getFavorites() {
        Set<Charset> result = widelyKnownCharsets();
        result.addAll(myMapping.values());
        result.add(getDefaultCharset());
        return result;
    }

    @NotNull
    static Set<Charset> widelyKnownCharsets() {
        Set<Charset> result = new HashSet<>();
        result.add(CharsetToolkit.UTF8_CHARSET);
        result.add(CharsetToolkit.getDefaultSystemCharset());
        result.add(CharsetToolkit.UTF_16_CHARSET);
        result.add(CharsetToolkit.forName("ISO-8859-1"));
        result.add(CharsetToolkit.forName("US-ASCII"));
        result.add(EncodingManager.getInstance().getDefaultCharset());
        result.add(EncodingManager.getInstance().getDefaultCharsetForPropertiesFiles(null));
        result.remove(null);
        return result;
    }

    private boolean processSubFiles(@Nullable("null means all in the project") VirtualFile file,
                                    @NotNull final Processor<VirtualFile> processor) {
        if (file == null) {
            for (VirtualFile virtualFile : ProjectRootManager.getInstance(myProject).getContentRoots()) {
                if (!processSubFiles(virtualFile, processor)) {return false;}
            }
            return true;
        }

        return VirtualFileVisitor.CONTINUE == VfsUtilCore.visitChildrenRecursively(file, new VirtualFileVisitor() {
            @Override
            public boolean visitFile(@NotNull final VirtualFile file) {
                return processor.process(file);
            }
        });
    }

    //retrieves encoding for the Project node
    @Override
    @NotNull
    public Charset getDefaultCharset() {
        Charset charset = myProjectCharset;
        // if the project charset was not specified, use the IDE encoding, save this back
        return charset == null ? myIdeEncodingManager.getDefaultCharset() : charset;
    }

    private static final ThreadLocal<Boolean> SUPPRESS_RELOAD = new ThreadLocal<>();

    static void suppressReloadDuring(@NotNull Runnable action) {
        Boolean old = SUPPRESS_RELOAD.get();
        try {
            SUPPRESS_RELOAD.set(Boolean.TRUE);
            action.run();
        } finally {
            SUPPRESS_RELOAD.set(old);
        }
    }

    private void tryStartReloadWithProgress(@NotNull final Runnable reloadAction) {
        Boolean suppress = SUPPRESS_RELOAD.get();
        if (suppress.equals(Boolean.TRUE)) {return;}
        FileDocumentManager.getInstance().saveAllDocuments();  // consider all files as unmodified
        ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> suppressReloadDuring(reloadAction),
            "Reload Files", false, myProject);
    }

    private void reloadAllFilesUnder(@Nullable final VirtualFile root) {
        tryStartReloadWithProgress(() -> processSubFiles(root, file -> {
            if (!(file instanceof VirtualFileSystemEntry)) {return true;}
            Document cachedDocument = FileDocumentManager.getInstance().getCachedDocument(file);
            if (cachedDocument != null) {
                ProgressManager.progress("Reloading file...", file.getPresentableUrl());
                TransactionGuard.submitTransaction(myProject, () -> reload(file));
            }
            // for not loaded files deep under project, reset encoding to give them chance re-detect the right one later
            else if (file.isCharsetSet() && !file.equals(root)) {
                file.setCharset(null);
            }
            return true;
        }));
    }

    @Override
    public boolean isNative2Ascii(@NotNull final VirtualFile virtualFile) {
        return virtualFile.getFileType() == StdFileTypes.PROPERTIES && myNative2AsciiForPropertiesFiles;
    }

    @Override
    public boolean isNative2AsciiForPropertiesFiles() {
        return myNative2AsciiForPropertiesFiles;
    }

    @Override
    public void setNative2AsciiForPropertiesFiles(final VirtualFile virtualFile, final boolean native2Ascii) {
        if (myNative2AsciiForPropertiesFiles != native2Ascii) {
            myNative2AsciiForPropertiesFiles = native2Ascii;
            myIdeEncodingManager.firePropertyChange(null, PROP_NATIVE2ASCII_SWITCH, !native2Ascii, native2Ascii);
        }
    }

    @NotNull // empty means system default
    @Override
    public String getDefaultCharsetName() {
        Charset charset = getEncoding(null, false);
        return charset == null ? "" : charset.name();
    }

    @Override
    public void setDefaultCharsetName(@NotNull String name) {
        setEncoding(null, name.isEmpty() ? null : CharsetToolkit.forName(name));
    }

    @Override
    @Nullable
    public Charset getDefaultCharsetForPropertiesFiles(@Nullable final VirtualFile virtualFile) {
        return myDefaultCharsetForPropertiesFiles;
    }

    @Override
    public void setDefaultCharsetForPropertiesFiles(@Nullable final VirtualFile virtualFile,
                                                    @Nullable Charset charset) {
        Charset old = myDefaultCharsetForPropertiesFiles;
        if (!Comparing.equal(old, charset)) {
            myDefaultCharsetForPropertiesFiles = charset;
            myIdeEncodingManager.firePropertyChange(null, PROP_PROPERTIES_FILES_ENCODING, old, charset);
        }
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener,
                                          @NotNull Disposable parentDisposable) {
        myIdeEncodingManager.addPropertyChangeListener(listener, parentDisposable);
    }

    @Override
    @Nullable
    public Charset getCachedCharsetFromContent(@NotNull Document document) {
        return myIdeEncodingManager.getCachedCharsetFromContent(document);
    }

    public enum BOMForNewUTF8Files {
        ALWAYS("with BOM"),
        NEVER("with NO BOM"),
        WINDOWS_ONLY("with BOM under Windows, with no BOM otherwise");

        private final String name;

        BOMForNewUTF8Files(@NotNull String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Override
    public boolean shouldAddBOMForNewUtf8File() {
        switch (myBOMForNewUTF8Files) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            case WINDOWS_ONLY:
                return SystemInfo.isWindows;
            default:
                throw new IllegalStateException(myBOMForNewUTF8Files.toString());
        }
    }
}
