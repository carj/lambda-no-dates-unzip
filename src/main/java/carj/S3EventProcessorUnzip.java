package carj;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.event.S3EventNotification.S3EventNotificationRecord;
import com.amazonaws.services.s3.model.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.FileTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


public class S3EventProcessorUnzip implements RequestHandler<S3Event, String> {


    public static String replaceCharAt(String s, int pos, char c) {
        return s.substring(0,pos) + c + s.substring(pos+1);
    }

    @Override
    public String handleRequest(S3Event s3Event, Context context) {
        try {
            for (S3EventNotificationRecord record : s3Event.getRecords()) {
                String srcBucket = record.getS3().getBucket().getName();

                // Object key may have spaces or unicode non-ASCII characters.
                String srcKey = record.getS3().getObject().getKey().replace('+', ' ');
                srcKey = URLDecoder.decode(srcKey, "UTF-8");

                // Detect file type
                Matcher matcher = Pattern.compile(".*\\.([^\\.]*)").matcher(srcKey);
                if (!matcher.matches()) {
                    System.out.println("Unable to detect file type for key " + srcKey);
                    return "";
                }
                String extension = matcher.group(1).toLowerCase();
                if (!"zip".equals(extension)) {
                    System.out.println("Skipping non-zip file " + srcKey + " with extension " + extension);
                    return "";
                }
                System.out.println("Extracting zip file " + srcBucket + "/" + srcKey);

                // Download the zip from S3 into a stream
                AmazonS3 s3Client = new AmazonS3Client();
                S3Object s3Object = s3Client.getObject(new GetObjectRequest(srcBucket, srcKey));

                //StandardCharsets.ISO_8859_1
                ZipInputStream zis = new ZipInputStream(s3Object.getObjectContent(), StandardCharsets.ISO_8859_1);
                ZipEntry entry = zis.getNextEntry();

                while (entry != null) {

                    String fileName = entry.getName();

                    int pos = -1;
                    for (int count=0; count < fileName.length(); count++) {
                        if (fileName.codePointAt(count) == 156) {
                            pos = count;
                        }
                    }

                    String utf_filename = fileName;
                    if (pos > -1) {
                        char c = '\u00A3';
                        utf_filename = replaceCharAt(fileName, pos, c);
                    }


                    String mimeType = FileMimeType.fromExtension(FilenameUtils.getExtension(fileName)).mimeType();
                    System.out.println("Extracting " + fileName + ", compressed: " + entry.getCompressedSize() + " bytes, extracted: " + entry.getSize() + " bytes, mimetype: " + mimeType);


                    ByteBufferOutputStream outputStream = new ByteBufferOutputStream();
                    long bytesCopied = IOUtils.copyLarge(zis, outputStream);

                    ByteArrayInputStream is = new ByteArrayInputStream(outputStream.getBuffer());

                    ObjectMetadata meta = new ObjectMetadata();
                    meta.setContentLength(outputStream.size());
                    meta.setContentType(mimeType);

                    String key = String.format("%s%s", FilenameUtils.getFullPath(srcKey), utf_filename);
                    s3Client.putObject(srcBucket, key, is, meta);

                    is.close();
                    outputStream.close();

                    entry = zis.getNextEntry();
                }
                zis.closeEntry();
                zis.close();

                //delete zip file when done
                System.out.println("Deleting zip file " + srcBucket + "/" + srcKey + "...");
                s3Client.deleteObject(new DeleteObjectRequest(srcBucket, srcKey));
                System.out.println("Done deleting");
            }
            return "Ok";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
