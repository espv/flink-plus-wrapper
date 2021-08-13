package org.apache.flink.experiments;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

public class FileToSend  {
    File file;
    final static int GB = 1000000000;
    byte[] fileAsBytes;
    DataInputStream inputStream;

    FileToSend(File file) {
        this.file = file;
        try {
            this.inputStream = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }

    FileToSend(FileToSend fts) {
        this.file = fts.file;
        this.inputStream = fts.inputStream;
        this.fileAsBytes = fts.fileAsBytes;
    }

    int loadFile() throws IOException {
        try {
            fileAsBytes = inputStream.readNBytes(Math.min(GB, inputStream.available()));
            return inputStream.available();
        } catch (IOException e) {
            e.printStackTrace();
            throw e;
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
