/* ====================================================================
   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
==================================================================== */

package org.apache.poi.hwpf.usermodel;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.zip.InflaterInputStream;

import org.apache.commons.io.input.UnsynchronizedByteArrayInputStream;
import org.apache.commons.io.output.UnsynchronizedByteArrayOutputStream;
import org.apache.logging.log4j.Logger;
import org.apache.poi.ddf.EscherBSERecord;
import org.apache.poi.ddf.EscherBlipRecord;
import org.apache.poi.ddf.EscherComplexProperty;
import org.apache.poi.ddf.EscherContainerRecord;
import org.apache.poi.ddf.EscherOptRecord;
import org.apache.poi.ddf.EscherProperty;
import org.apache.poi.ddf.EscherPropertyTypes;
import org.apache.poi.ddf.EscherRecord;
import org.apache.poi.ddf.EscherRecordTypes;
import org.apache.poi.ddf.EscherSimpleProperty;
import org.apache.poi.hwpf.model.PICF;
import org.apache.poi.hwpf.model.PICFAndOfficeArtData;
import org.apache.poi.logging.PoiLogManager;
import org.apache.poi.sl.image.ImageHeaderPNG;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.StringUtil;
import org.apache.poi.util.Units;

/**
 * Represents embedded picture extracted from Word Document
 */
public final class Picture {
    private static final Logger LOGGER = PoiLogManager.getLogger(Picture.class);

    private static final byte[] COMPRESSED1 = { (byte) 0xFE, 0x78, (byte) 0xDA };

    private static final byte[] COMPRESSED2 = { (byte) 0xFE, 0x78, (byte) 0x9C };

    private static final byte[] IHDR = new byte[] { 'I', 'H', 'D', 'R' };

    @Deprecated
    private static final byte[] PNG = new byte[] { (byte) 0x89, 0x50, 0x4E,
            0x47, 0x0D, 0x0A, 0x1A, 0x0A };

    private static int getBigEndianInt( byte[] data, int offset )
    {
        return ( ( ( data[offset] & 0xFF ) << 24 )
                + ( ( data[offset + 1] & 0xFF ) << 16 )
                + ( ( data[offset + 2] & 0xFF ) << 8 ) + ( data[offset + 3] & 0xFF ) );
    }

    private static int getBigEndianShort( byte[] data, int offset )
    {
        return ( ( ( data[offset] & 0xFF ) << 8 ) + ( data[offset + 1] & 0xFF ) );
    }

    private static boolean matchSignature( byte[] pictureData,
            byte[] signature, int offset )
    {
        boolean matched = offset < pictureData.length;
        for ( int i = 0; ( i + offset ) < pictureData.length
                && i < signature.length; i++ )
        {
            if ( pictureData[i + offset] != signature[i] )
            {
                matched = false;
                break;
            }
        }
        return matched;
    }

    private PICF _picf;
    private PICFAndOfficeArtData _picfAndOfficeArtData;
    private final List<? extends EscherRecord> _blipRecords;

    private byte[] content;
    private int dataBlockStartOfsset;

    private int height = -1;
    private int width = -1;

    /**
     * Builds a Picture object for a Picture stored as
     *  Escher.
     * TODO We need to pass in the PICF data too somehow!
     */
    public Picture( EscherBlipRecord blipRecord )
    {
       this._blipRecords = Collections.singletonList(blipRecord);
    }

    /**
     * Builds a Picture object for a Picture stored in the
     *  DataStream
     */
    public Picture( int dataBlockStartOfsset, byte[] _dataStream, boolean fillBytes ) { // NOSONAR
        _picfAndOfficeArtData = new PICFAndOfficeArtData( _dataStream, dataBlockStartOfsset );
        _picf = _picfAndOfficeArtData.getPicf();

        this.dataBlockStartOfsset = dataBlockStartOfsset;

        _blipRecords = _picfAndOfficeArtData.getBlipRecords();

        if ( fillBytes ) {
            fillImageContent();
        }
    }

    private void fillImageContent() {
        if ( content != null && content.length > 0 ) {
            return;
        }

        byte[] rawContent = getRawContent();

        /*
         * HACK: Detect compressed images. In reality there should be some way
         * to determine this from the first 32 bytes, but I can't see any
         * similarity between all the samples I have obtained, nor any
         * similarity in the data block contents.
         */
        if ( matchSignature( rawContent, COMPRESSED1, 32 )
                || matchSignature( rawContent, COMPRESSED2, 32 ) ) {
            try (UnsynchronizedByteArrayInputStream bis = UnsynchronizedByteArrayInputStream.builder().setByteArray(rawContent).
                    setOffset(33).setLength(rawContent.length - 33).get();
                 InflaterInputStream in = new InflaterInputStream(bis);
                 UnsynchronizedByteArrayOutputStream out = UnsynchronizedByteArrayOutputStream.builder().get()) {

                IOUtils.copy(in, out);
                content = out.toByteArray();
            } catch (IOException e) {
                /*
                 * Problems reading from the actual ByteArrayInputStream should
                 * never happen so this will only ever be a ZipException.
                 */
                LOGGER.atInfo().withThrowable(e).log("Possibly corrupt compression or non-compressed data");
            }
        } else {
            // Raw data is not compressed.
            content = new ImageHeaderPNG(rawContent).extractPNG();
        }
    }

    private void fillJPGWidthHeight()
    {
        /*
         * http://www.codecomments.com/archive281-2004-3-158083.html
         *
         * Algorithm proposed by Patrick TJ McPhee:
         *
         * read 2 bytes make sure they are 'ffd8'x repeatedly: read 2 bytes make
         * sure the first one is 'ff'x if the second one is 'd9'x stop else if
         * the second one is c0 or c2 (or possibly other values ...) skip 2
         * bytes read one byte into depth read two bytes into height read two
         * bytes into width else read two bytes into length skip forward
         * length-2 bytes
         *
         * Also used Ruby code snippet from:
         * http://www.bigbold.com/snippets/posts/show/805 for reference
         */
        byte[] jpegContent = getContent();

        int pointer = 2;
        int firstByte;
        int secondByte;
        int endOfPicture = jpegContent.length;
        while ( pointer < endOfPicture - 1 )
        {
            do
            {
                firstByte = jpegContent[pointer];
                secondByte = jpegContent[pointer + 1];
                pointer += 2;
            }
            while ( !( firstByte == (byte) 0xFF ) && pointer < endOfPicture - 1 );

            if ( firstByte == ( (byte) 0xFF ) && pointer < endOfPicture - 1 )
            {
                if ( secondByte == (byte) 0xD9 || secondByte == (byte) 0xDA )
                {
                    break;
                }
                else if ( ( secondByte & 0xF0 ) == 0xC0
                        && secondByte != (byte) 0xC4
                        && secondByte != (byte) 0xC8
                        && secondByte != (byte) 0xCC )
                {
                    pointer += 5;
                    this.height = getBigEndianShort( jpegContent, pointer );
                    this.width = getBigEndianShort( jpegContent, pointer + 2 );
                    break;
                }
                else
                {
                    pointer++;
                    pointer++;
                    int length = getBigEndianShort( jpegContent, pointer );
                    pointer += length;
                }
            }
            else
            {
                pointer++;
            }
        }
    }

    void fillPNGWidthHeight()
    {
        byte[] pngContent = getContent();
        /*
         * Used PNG file format description from
         * http://www.wotsit.org/download.asp?f=png
         */
        int HEADER_START = PNG.length + 4;
        if ( matchSignature( pngContent, IHDR, HEADER_START ) )
        {
            int IHDR_CHUNK_WIDTH = HEADER_START + 4;
            this.width = getBigEndianInt( pngContent, IHDR_CHUNK_WIDTH );
            this.height = getBigEndianInt( pngContent, IHDR_CHUNK_WIDTH + 4 );
        }
    }

    private void fillWidthHeight()
    {
        PictureType pictureType = suggestPictureType();
        // trying to extract width and height from pictures content:
        switch ( pictureType )
        {
        case JPEG:
            fillJPGWidthHeight();
            break;
        case PNG:
            fillPNGWidthHeight();
            break;
        default:
            // unsupported;
            break;
        }
    }

    /**
     * @return picture's content as byte array
     */
    public byte[] getContent()
    {
        fillImageContent();
        return content;
    }

    /**
     * @return The amount the picture has been cropped on the left in twips
     */
    public int getDxaCropLeft() {
        return _picf.getDxaReserved1();
    }

    /**
     * The location, expressed as a fraction of the image width, of the left side of
     * the crop rectangle. A value of 0 specifies that the left side of the image is uncropped.
     * Positive values specify cropping into the image. Negative values specify cropping out from the
     * image. The default value for this property is 0.
     *
     * @return the left crop percent
     */
    public double getCropLeft() {
        return getCrop(EscherPropertyTypes.BLIP__CROPFROMLEFT);
    }

    /**
     * @return The amount the picture has been cropped on the right in twips
     */
    public int getDxaCropRight() {
        return _picf.getDxaReserved2();
    }

    /**
     * the location of the right side, expressed as a fraction of the image width, of
     * the crop rectangle. A value of 0 specifies that the right side of the image is uncropped.
     * Positive values specify cropping into the image. Negative values specify cropping out from the
     * image. The default value for this property is 0.
     *
     * @return the right crop percent
     */
    public double getCropRight() {
        return getCrop(EscherPropertyTypes.BLIP__CROPFROMRIGHT);
    }

    /**
     * Gets the initial width of the picture, in twips, prior to cropping or
     * scaling.
     *
     * @return the initial width of the picture in twips
     */
    public int getDxaGoal()
    {
        return _picf.getDxaGoal();
    }

    /**
     * @return The amount the picture has been cropped on the bottom in twips
     */
    public int getDyaCropBottom() {
        return _picf.getDyaReserved2();
    }

    /**
     * the location, expressed as a fraction of the image height, of the bottom of
     * the crop rectangle. A value of 0 specifies that the bottom of the image is uncropped.
     * Positive values specify cropping into the image. Negative values specify cropping out from the
     * image. The default value for this property is 0
     *
     * @return the bottom crop percent
     */
    public double getCropBottom() {
        return getCrop(EscherPropertyTypes.BLIP__CROPFROMBOTTOM);
    }

    /**
     * @return The amount the picture has been cropped on the top in twips
     */
    public int getDyaCropTop() {
        return _picf.getDyaReserved1();
    }

    /**
     * the location, expressed as a fraction of the image height, of the top of the crop
     * rectangle. A value of 0 specifies that the top of the image is uncropped. Positive values
     * specify cropping into the image. Negative values specify cropping out from the image. The default
     * value for this property is 0.
     *
     * @return the top crop percent
     */
    public double getCropTop() {
        return getCrop(EscherPropertyTypes.BLIP__CROPFROMTOP);
    }

    private double getCrop(EscherPropertyTypes propType) {
        EscherContainerRecord shape;
        if (_picfAndOfficeArtData != null && (shape = _picfAndOfficeArtData.getShape()) != null) {
            EscherOptRecord optRecord = shape.getChildById(EscherRecordTypes.OPT.typeID);
            if (optRecord != null) {
                EscherProperty property = optRecord.lookup(propType);
                if (property instanceof EscherSimpleProperty) {
                    EscherSimpleProperty simpleProperty = (EscherSimpleProperty) property;
                    return Units.fixedPointToDouble(simpleProperty.getPropertyValue());
                }
            }
        }
        return 0;
    }

    /**
     * Gets the initial height of the picture, in twips, prior to cropping or
     * scaling.
     *
     * @return the initial width of the picture in twips
     */
    public int getDyaGoal()
    {
        return _picf.getDyaGoal();
    }

    /**
     * returns pixel height of the picture or -1 if dimensions determining was
     * failed
     */
    public int getHeight()
    {
        if ( height == -1 )
        {
            fillWidthHeight();
        }
        return height;
    }

    /**
     * @return Horizontal scaling factor supplied by user expressed in .001%
     *         units
     */
    public int getHorizontalScalingFactor()
    {
        return _picf.getMx();
    }

    /**
     * Returns the MIME type for the image
     *
     * @return MIME-type for known types of image or "image/unknown" if unknown
     */
    public String getMimeType()
    {
        return suggestPictureType().getMime();
    }

    /**
     * Returns picture's content as stored in the Word file, i.e. possibly in
     * compressed form.
     *
     * @return picture's content as it stored in Word file or an empty byte array
     *      if it cannot be read.
     */
    public byte[] getRawContent()
    {
        if (_blipRecords.size() != 1) {
           return new byte[0];
        }

        EscherRecord escherRecord = _blipRecords.get( 0 );
        if ( escherRecord instanceof EscherBlipRecord )
        {
            return ( (EscherBlipRecord) escherRecord ).getPicturedata();
        }

        if ( escherRecord instanceof EscherBSERecord )
        {
            EscherBlipRecord blip = ( (EscherBSERecord) escherRecord ).getBlipRecord();
            if (blip != null) {
                return blip.getPicturedata();

            }
        }
        return new byte[0];
    }

    /**
     *
     * @return size in bytes of the picture
     */
    public int getSize()
    {
        return getContent().length;
    }

    /**
     * @return The offset of this picture in the picture bytes, used when
     *         matching up with {@link CharacterRun#getPicOffset()}
     */
    public int getStartOffset()
    {
        return dataBlockStartOfsset;
    }

    /**
     * @return Vertical scaling factor supplied by user expressed in .001% units
     */
    public int getVerticalScalingFactor()
    {
        return _picf.getMy();
    }

    /**
     * returns pixel width of the picture or -1 if dimensions determining was
     * failed
     */
    public int getWidth()
    {
        if ( width == -1 )
        {
            fillWidthHeight();
        }
        return width;
    }

    /**
     * returns the description stored in the alternative text
     *
     * @return pictue description
     */
    public String getDescription()
    {
       for(EscherRecord escherRecord : _picfAndOfficeArtData.getShape()){
          if(escherRecord instanceof EscherOptRecord){
             EscherOptRecord escherOptRecord = (EscherOptRecord) escherRecord;
             for(EscherProperty property : escherOptRecord.getEscherProperties()){
                if (EscherPropertyTypes.GROUPSHAPE__DESCRIPTION.propNumber == property.getPropertyNumber()){
                   byte[] complexData = ((EscherComplexProperty)property).getComplexData();
                   return StringUtil.getFromUnicodeLE(complexData,0,complexData.length/2-1);
                }
             }
          }
       }

       return null;
    }

    /**
     * tries to suggest extension for picture's file by matching signatures of
     * popular image formats to first bytes of picture's contents
     *
     * @return suggested file extension
     */
    public String suggestFileExtension()
    {
        return suggestPictureType().getExtension();
    }

    /**
     * Tries to suggest a filename: hex representation of picture structure
     * offset in "Data" stream plus extension that is tried to determine from
     * first byte of picture's content.
     *
     * @return suggested file name
     */
    public String suggestFullFileName()
    {
        String fileExt = suggestFileExtension();
        return Integer.toHexString( dataBlockStartOfsset )
                + ( fileExt.length() > 0 ? "." + fileExt : "" );
    }

    public PictureType suggestPictureType() {
        if (_blipRecords.size() != 1 ) {
            return PictureType.UNKNOWN;
        }

        EscherRecord escherRecord = _blipRecords.get( 0 );
        if (escherRecord instanceof EscherBSERecord) {
            EscherBSERecord bseRecord = (EscherBSERecord) escherRecord;
            switch ( bseRecord.getBlipTypeWin32() ) {
                case 0x00:
                    return PictureType.UNKNOWN;
                case 0x01:
                    return PictureType.UNKNOWN;
                case 0x02:
                    return PictureType.EMF;
                case 0x03:
                    return PictureType.WMF;
                case 0x04:
                    return PictureType.PICT;
                case 0x05:
                    return PictureType.JPEG;
                case 0x06:
                    return PictureType.PNG;
                case 0x07:
                    return PictureType.BMP;
                case 0x11:
                    return PictureType.TIFF;
                case 0x12:
                    return PictureType.JPEG;
                default:
                    return PictureType.UNKNOWN;
            }
        }

        Enum<?> recordType = escherRecord.getGenericRecordType();
        assert (recordType instanceof EscherRecordTypes);
        switch ((EscherRecordTypes)recordType) {
            case BLIP_EMF:
                return PictureType.EMF;
            case BLIP_WMF:
                return PictureType.WMF;
            case BLIP_PICT:
                return PictureType.PICT;
            case BLIP_JPEG:
                return PictureType.JPEG;
            case BLIP_PNG:
                return PictureType.PNG;
            case BLIP_DIB:
                return PictureType.BMP;
            case BLIP_TIFF:
                return PictureType.TIFF;
            default:
                return PictureType.UNKNOWN;
        }
    }

    /**
     * Writes Picture's content bytes to specified OutputStream. Is useful when
     * there is need to write picture bytes directly to stream, omitting its
     * representation in memory as distinct byte array.
     *
     * @param out
     *            a stream to write to
     * @throws IOException
     *             if some exception is occured while writing to specified out
     */
    public void writeImageContent( OutputStream out ) throws IOException
    {
        byte[] c = getContent();
        if ( c != null && c.length > 0 )
        {
            out.write( c, 0, c.length );
        }
    }

}
