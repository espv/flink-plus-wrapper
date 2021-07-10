package org.apache.flink.experiments;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class FileToSend  {
    File file;
    byte[] fileAsBytes;

    FileToSend(File file) {
        this.file = file;
    }

    void loadFile() {
        try {
            fileAsBytes = FileUtils.readFileToByteArray(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class FileToReceive {
    byte[] fileAsBytes;
    String filename;

    FileToReceive(String filename, byte[] fileAsBytes) {
        this.fileAsBytes = fileAsBytes;
        this.filename = filename;
    }
}