package android.print;

import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import java.io.File;

public final class PdfPrintHelper {
    public interface Callback {
        void onSuccess(File file);
        void onFailure(String error);
    }

    private PdfPrintHelper() {}

    public static void print(
        final PrintDocumentAdapter adapter,
        final PrintAttributes attributes,
        final File outFile,
        final Callback callback
    ) {
        adapter.onLayout(null, attributes, null, new PrintDocumentAdapter.LayoutResultCallback() {
            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                try {
                    final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                        outFile,
                        ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE
                            | ParcelFileDescriptor.MODE_WRITE_ONLY
                    );
                    adapter.onWrite(
                        new PageRange[]{PageRange.ALL_PAGES},
                        pfd,
                        new CancellationSignal(),
                        new PrintDocumentAdapter.WriteResultCallback() {
                            @Override
                            public void onWriteFinished(PageRange[] pages) {
                                try { pfd.close(); } catch (Exception ignored) {}
                                callback.onSuccess(outFile);
                            }

                            @Override
                            public void onWriteFailed(CharSequence error) {
                                try { pfd.close(); } catch (Exception ignored) {}
                                callback.onFailure(error != null ? error.toString() : "PDF write failed");
                            }
                        }
                    );
                } catch (Exception e) {
                    callback.onFailure(e.getMessage() != null ? e.getMessage() : "PDF open failed");
                }
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                callback.onFailure(error != null ? error.toString() : "PDF layout failed");
            }
        }, null);
    }
}
