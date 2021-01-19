package io.questdb.cutlass.line.tcp;

import java.io.Closeable;

import io.questdb.std.Numbers;
import io.questdb.std.NumericException;
import io.questdb.std.ObjList;
import io.questdb.std.Unsafe;
import io.questdb.std.str.DirectByteCharSequence;

public class NewLineProtoParser implements Closeable {
    public static long NULL_TIMESTAMP = Long.MIN_VALUE;

    public enum ParseResult {
        MEASUREMENT_COMPLETE, BUFFER_UNDERFLOW, ERROR
    };

    public enum ErrorCode {
        EMPTY_LINE, NO_FIELDS, INCOMPLETE_TAG, INCOMPLETE_FIELD, INVALID_FIELD_SEPERATOR, INVALID_TIMESTAMP, INVALID_FIELD_VALUE
    };

    private static final byte ENTITY_TYPE_NONE = (byte) 0xff;
    public static final byte ENTITY_TYPE_TAG = 0;
    public static final byte ENTITY_TYPE_FLOAT = 1;
    public static final byte ENTITY_TYPE_INTEGER = 2;
    public static final byte ENTITY_TYPE_STRING = 3;
    public static final byte ENTITY_TYPE_BOOLEAN = 4;
    public static final byte ENTITY_TYPE_LONG256 = 5;
    public static final int N_ENTITY_TYPES = ENTITY_TYPE_LONG256 + 1;

    private final DirectByteCharSequence measurementName = new DirectByteCharSequence();
    private final DirectByteCharSequence charSeq = new DirectByteCharSequence();
    private ObjList<ProtoEntity> entityCache = new ObjList<>();
    private long bufAt;
    private long entityLo;
    private boolean tagsComplete;
    private int nEscapedChars;
    private int nEntities;
    private ProtoEntity currentEntity;
    private ErrorCode errorCode;
    private EntityHandler entityHandler;
    private long timestamp;

    private final EntityHandler entityTableHandler = this::expectTableName;
    private final EntityHandler entityNameHandler = this::expectEntityName;
    private final EntityHandler entityValueHandler = this::expectEntityValue;
    private final EntityHandler entityTimestampHandler = this::expectTimestamp;
    private final EntityHandler entityEndOfLineHandler = this::expectEndoOfLine;

    public NewLineProtoParser of(long bufLo) {
        this.bufAt = bufLo - 1;
        startNextMeasurement();
        return this;
    }

    public ParseResult parseMeasurement(long bufHi) {
        assert bufAt != 0 && bufHi >= bufAt;
        while (bufAt < bufHi) {
            byte b = Unsafe.getUnsafe().getByte(bufAt);
            boolean endOfLine = false;
            switch (b) {
                case (byte) '\n':
                case (byte) '\r':
                    endOfLine = true;
                    b = '\n';
                case (byte) '=':
                case (byte) ',':
                case (byte) ' ':
                    if (!entityHandler.completeEntity(b)) {
                        if (errorCode == ErrorCode.EMPTY_LINE) {
                            // An empty line
                            bufAt++;
                            entityLo = bufAt;
                            break;
                        }
                        return ParseResult.ERROR;
                    }
                    if (endOfLine) {
                        if (nEntities > 0) {
                            entityHandler = entityEndOfLineHandler;
                            return ParseResult.MEASUREMENT_COMPLETE;
                        }
                        errorCode = ErrorCode.NO_FIELDS;
                        return ParseResult.ERROR;
                    }
                    bufAt++;
                    nEscapedChars = 0;
                    entityLo = bufAt;
                    break;

                case (byte) '\\':
                    if ((bufAt + 1) >= bufHi) {
                        return ParseResult.BUFFER_UNDERFLOW;
                    }
                    nEscapedChars++;
                    bufAt++;
                    b = Unsafe.getUnsafe().getByte(bufAt);

                default:
                    if (nEscapedChars > 0) {
                        Unsafe.getUnsafe().putByte(bufAt - nEscapedChars, b);
                    }
                    bufAt++;
                    break;
            }
        }
        return ParseResult.BUFFER_UNDERFLOW;
    }

    public void startNextMeasurement() {
        bufAt++;
        nEscapedChars = 0;
        entityLo = bufAt;
        errorCode = null;
        tagsComplete = false;
        nEntities = 0;
        currentEntity = null;
        entityHandler = entityTableHandler;
        timestamp = NULL_TIMESTAMP;
    }

    public ParseResult skipMeasurement(long bufHi) {
        assert bufAt != 0 && bufHi >= bufAt;
        while (bufAt < bufHi) {
            byte b = Unsafe.getUnsafe().getByte(bufAt);
            if (b == (byte) '\n' || b == (byte) '\r') {
                return ParseResult.MEASUREMENT_COMPLETE;
            }
            bufAt++;
        }
        return ParseResult.BUFFER_UNDERFLOW;
    }

    public long getBufferAddress() {
        return bufAt;
    }

    public DirectByteCharSequence getMeasurementName() {
        return measurementName;
    }

    public int getnEntities() {
        return nEntities;
    }

    public ProtoEntity getEntity(int n) {
        assert n < nEntities;
        return entityCache.get(n);
    }

    public boolean hasTimestamp() {
        return timestamp != NULL_TIMESTAMP;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public void clear() {
        bufAt = 0;
    }

    @Override
    public void close() {
    }

    private boolean expectTableName(byte endOfEntityByte) {
        tagsComplete = endOfEntityByte == (byte) ' ';
        if (endOfEntityByte == (byte) ',' || tagsComplete) {
            measurementName.of(entityLo, bufAt);
            entityHandler = entityNameHandler;
            return true;
        }

        if (entityLo == bufAt) {
            errorCode = ErrorCode.EMPTY_LINE;
        } else {
            errorCode = ErrorCode.NO_FIELDS;
        }
        return false;
    }

    private boolean expectEntityName(byte endOfEntityByte) {
        if (endOfEntityByte == (byte) '=') {
            if (entityCache.size() <= nEntities) {
                currentEntity = new ProtoEntity();
                entityCache.add(currentEntity);
            } else {
                currentEntity = entityCache.get(nEntities);
                currentEntity.clear();
            }

            nEntities++;
            currentEntity.setName();
            entityHandler = entityValueHandler;
            return true;
        }

        boolean emptyEntity = bufAt == entityLo;
        if (emptyEntity) {
            if (endOfEntityByte == (byte) ' ') {
                if (tagsComplete) {
                    entityHandler = entityTimestampHandler;
                } else {
                    tagsComplete = true;
                }
                return true;
            }

            if (endOfEntityByte == (byte) '\n') {
                return true;
            }
        }

        if (tagsComplete) {
            errorCode = ErrorCode.INCOMPLETE_FIELD;
        } else {
            errorCode = ErrorCode.INCOMPLETE_TAG;
        }
        return false;
    }

    private boolean expectEntityValue(byte endOfEntityByte) {
        boolean endOfSet = endOfEntityByte == (byte) ' ';
        if (endOfSet || endOfEntityByte == (byte) ',' || endOfEntityByte == (byte) '\n') {
            if (currentEntity.setValue()) {
                if (endOfSet) {
                    if (tagsComplete) {
                        entityHandler = entityTimestampHandler;
                    } else {
                        entityHandler = entityNameHandler;
                        tagsComplete = true;
                    }
                } else {
                    entityHandler = entityNameHandler;
                }
                return true;
            }

            errorCode = ErrorCode.INVALID_FIELD_VALUE;
            return false;
        }

        errorCode = ErrorCode.INVALID_FIELD_SEPERATOR;
        return false;
    }

    private boolean expectTimestamp(byte endOfEntityByte) {
        try {
            if (endOfEntityByte == (byte) '\n') {
                timestamp = Numbers.parseLong(charSeq.of(entityLo, bufAt - nEscapedChars));
                entityHandler = null;
                return true;
            }
            errorCode = ErrorCode.INVALID_FIELD_SEPERATOR;
            return false;
        } catch (NumericException ex) {
            errorCode = ErrorCode.INVALID_TIMESTAMP;
            return false;
        }
    }

    private boolean expectEndoOfLine(byte endOfEntityByte) {
        assert endOfEntityByte == '\n';
        return true;
    }

    private interface EntityHandler {
        boolean completeEntity(byte endOfEntityByte);
    }

    public class ProtoEntity {
        private final DirectByteCharSequence name = new DirectByteCharSequence();
        private final DirectByteCharSequence value = new DirectByteCharSequence();
        private byte type = ENTITY_TYPE_NONE;
        private long integerValue;
        private boolean booleanValue;
        private double floatValue;;

        private void setName() {
            name.of(entityLo, bufAt - nEscapedChars);
        }

        private boolean setValue() {
            assert type == ENTITY_TYPE_NONE;
            long bufHi = bufAt - nEscapedChars;
            int valueLen = (int) (bufHi - entityLo);
            if (valueLen <= 0) {
                return false;
            }
            value.of(entityLo, bufHi);
            if (tagsComplete) {
                byte lastByte = value.byteAt(valueLen - 1);
                return parse(lastByte, valueLen);
            }
            type = ENTITY_TYPE_TAG;
            return true;
        }

        private boolean parse(byte lastByte, int valueLen) {
            try {
                switch (lastByte) {
                    case 'i':
                        if (valueLen > 1 && value.charAt(1) != 'x') {
                            charSeq.of(value.getLo(), value.getHi() - 1);
                            integerValue = Numbers.parseLong(charSeq);
                            type = ENTITY_TYPE_INTEGER;
                        } else {
                            if (valueLen < 4 || value.charAt(0) != '0') {
                                return false;
                            }
                            value.of(value.getLo(), value.getHi() - 1);
                            type = ENTITY_TYPE_LONG256;
                        }
                        return true;
                    case 'e': {
                        // tru(e)
                        // fals(e)
                        byte b = value.byteAt(0);
                        booleanValue = b == 't' || b == 'T';
                        type = ENTITY_TYPE_BOOLEAN;
                        return true;
                    }
                    case 't':
                    case 'T':
                        // t
                        // T
                        booleanValue = true;
                        type = ENTITY_TYPE_BOOLEAN;
                        return true;
                    case 'f':
                    case 'F':
                        // f
                        // F
                        booleanValue = false;
                        type = ENTITY_TYPE_BOOLEAN;
                        return true;
                    case '"': {
                        byte b = value.byteAt(0);
                        if (valueLen > 1 && b == '"') {
                            value.of(value.getLo() + 1, value.getHi() - 1);
                            type = ENTITY_TYPE_STRING;
                            return true;
                        }
                        return false;
                    }
                    default:
                        floatValue = Numbers.parseDouble(value);
                        type = ENTITY_TYPE_FLOAT;
                        return true;
                }
            } catch (NumericException ex) {
                return false;
            }
        }

        private void clear() {
            type = ENTITY_TYPE_NONE;
        }

        public byte getType() {
            return type;
        }

        public DirectByteCharSequence getName() {
            return name;
        }

        public DirectByteCharSequence getValue() {
            return value;
        }

        public long getIntegerValue() {
            return integerValue;
        }

        public double getFloatValue() {
            return floatValue;
        }

        public boolean getBooleanValue() {
            return booleanValue;
        }
    }
}
