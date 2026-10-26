package com.inkreader.nativeink;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.List;

/** Test APK only; plain Java so the independent provider needs no target APK Kotlin runtime. */
public class ImportFixtures extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/octet-stream"; }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        if(uri.getPath().endsWith("/children")) {
            MatrixCursor cursor=new MatrixCursor(new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE});
            List<String> parts=uri.getPathSegments();String parent=parts.get(parts.size()-2);
            if(parent.equals("root"))cursor.addRow(new Object[]{"folder-one","随笔",DocumentsContract.Document.MIME_TYPE_DIR});
            else cursor.addRow(new Object[]{"目录文件.txt","目录文件.txt","text/plain"});
            return cursor;
        }
        MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{uri.getLastPathSegment().equals("root") ? "导入目录" : uri.getLastPathSegment()});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(name==null || name.contains("/") || name.isEmpty())throw new FileNotFoundException();
        File file=new File(getContext().getCacheDir(),name);
        try {
            if(name.equals("文本.pdf") || name.equals("扫描.pdf")) {
                PdfDocument pdf=new PdfDocument();
                try {
                    PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(600,800,1).create());
                    Paint paint=new Paint();paint.setTextSize(22);
                    if(name.equals("文本.pdf"))page.getCanvas().drawText("PDF_REFLOW_TEXT native reading",40,100,paint);
                    pdf.finishPage(page);try(OutputStream out=new FileOutputStream(file)){pdf.writeTo(out);}
                }finally{pdf.close();}
            }else {
                InputStream input;
                if(name.equals("fixture-font.ttf")) {
                    File[] fonts=new File("/system/fonts").listFiles((dir,n)->n.endsWith(".ttf"));
                    if(fonts==null || fonts.length==0)throw new FileNotFoundException("No fixture font");
                    input=new FileInputStream(fonts[0]);
                }else input=getContext().getAssets().open("fixtures/"+name);
                try(InputStream in=input;OutputStream out=new FileOutputStream(file)) {
                    byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                }
            }
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        }catch(IOException e){throw new FileNotFoundException(e.getMessage());}
    }
    @Override public Uri insert(Uri uri,ContentValues values){return null;}
    @Override public int delete(Uri uri,String s,String[] args){return 0;}
    @Override public int update(Uri uri,ContentValues values,String s,String[] args){return 0;}
}
