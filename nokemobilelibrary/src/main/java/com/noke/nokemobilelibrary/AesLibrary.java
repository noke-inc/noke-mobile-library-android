package com.noke.nokemobilelibrary;

/**
 * Created by Spencer on 5/2/18.
 */

public class AesLibrary {
    // foreward sbox
    static public byte[] sbox = {
            //0     1    2      3     4    5     6     7      8    9     A      B    C     D     E     F
            (byte)0x63, (byte)0x7c, (byte)0x77, (byte)0x7b, (byte)0xf2, (byte)0x6b, (byte)0x6f, (byte)0xc5, (byte)0x30, (byte)0x01, (byte)0x67, (byte)0x2b, (byte)0xfe, (byte)0xd7, (byte)0xab, (byte)0x76, //0
            (byte)0xca, (byte)0x82, (byte)0xc9, (byte)0x7d, (byte)0xfa, (byte)0x59, (byte)0x47, (byte)0xf0, (byte)0xad, (byte)0xd4, (byte)0xa2, (byte)0xaf, (byte)0x9c, (byte)0xa4, (byte)0x72, (byte)0xc0, //1
            (byte)0xb7, (byte)0xfd, (byte)0x93, (byte)0x26, (byte)0x36, (byte)0x3f, (byte)0xf7, (byte)0xcc, (byte)0x34, (byte)0xa5, (byte)0xe5, (byte)0xf1, (byte)0x71, (byte)0xd8, (byte)0x31, (byte)0x15, //2
            (byte)0x04, (byte)0xc7, (byte)0x23, (byte)0xc3, (byte)0x18, (byte)0x96, (byte)0x05, (byte)0x9a, (byte)0x07, (byte)0x12, (byte)0x80, (byte)0xe2, (byte)0xeb, (byte)0x27, (byte)0xb2, (byte)0x75, //3
            (byte)0x09, (byte)0x83, (byte)0x2c, (byte)0x1a, (byte)0x1b, (byte)0x6e, (byte)0x5a, (byte)0xa0, (byte)0x52, (byte)0x3b, (byte)0xd6, (byte)0xb3, (byte)0x29, (byte)0xe3, (byte)0x2f, (byte)0x84, //4
            (byte)0x53, (byte)0xd1, (byte)0x00, (byte)0xed, (byte)0x20, (byte)0xfc, (byte)0xb1, (byte)0x5b, (byte)0x6a, (byte)0xcb, (byte)0xbe, (byte)0x39, (byte)0x4a, (byte)0x4c, (byte)0x58, (byte)0xcf, //5
            (byte)0xd0, (byte)0xef, (byte)0xaa, (byte)0xfb, (byte)0x43, (byte)0x4d, (byte)0x33, (byte)0x85, (byte)0x45, (byte)0xf9, (byte)0x02, (byte)0x7f, (byte)0x50, (byte)0x3c, (byte)0x9f, (byte)0xa8, //6
            (byte)0x51, (byte)0xa3, (byte)0x40, (byte)0x8f, (byte)0x92, (byte)0x9d, (byte)0x38, (byte)0xf5, (byte)0xbc, (byte)0xb6, (byte)0xda, (byte)0x21, (byte)0x10, (byte)0xff, (byte)0xf3, (byte)0xd2, //7
            (byte)0xcd, (byte)0x0c, (byte)0x13, (byte)0xec, (byte)0x5f, (byte)0x97, (byte)0x44, (byte)0x17, (byte)0xc4, (byte)0xa7, (byte)0x7e, (byte)0x3d, (byte)0x64, (byte)0x5d, (byte)0x19, (byte)0x73, //8
            (byte)0x60, (byte)0x81, (byte)0x4f, (byte)0xdc, (byte)0x22, (byte)0x2a, (byte)0x90, (byte)0x88, (byte)0x46, (byte)0xee, (byte)0xb8, (byte)0x14, (byte)0xde, (byte)0x5e, (byte)0x0b, (byte)0xdb, //9
            (byte)0xe0, (byte)0x32, (byte)0x3a, (byte)0x0a, (byte)0x49, (byte)0x06, (byte)0x24, (byte)0x5c, (byte)0xc2, (byte)0xd3, (byte)0xac, (byte)0x62, (byte)0x91, (byte)0x95, (byte)0xe4, (byte)0x79, //A
            (byte)0xe7, (byte)0xc8, (byte)0x37, (byte)0x6d, (byte)0x8d, (byte)0xd5, (byte)0x4e, (byte)0xa9, (byte)0x6c, (byte)0x56, (byte)0xf4, (byte)0xea, (byte)0x65, (byte)0x7a, (byte)0xae, (byte)0x08, //B
            (byte)0xba, (byte)0x78, (byte)0x25, (byte)0x2e, (byte)0x1c, (byte)0xa6, (byte)0xb4, (byte)0xc6, (byte)0xe8, (byte)0xdd, (byte)0x74, (byte)0x1f, (byte)0x4b, (byte)0xbd, (byte)0x8b, (byte)0x8a, //C
            (byte)0x70, (byte)0x3e, (byte)0xb5, (byte)0x66, (byte)0x48, (byte)0x03, (byte)0xf6, (byte)0x0e, (byte)0x61, (byte)0x35, (byte)0x57, (byte)0xb9, (byte)0x86, (byte)0xc1, (byte)0x1d, (byte)0x9e, //D
            (byte)0xe1, (byte)0xf8, (byte)0x98, (byte)0x11, (byte)0x69, (byte)0xd9, (byte)0x8e, (byte)0x94, (byte)0x9b, (byte)0x1e, (byte)0x87, (byte)0xe9, (byte)0xce, (byte)0x55, (byte)0x28, (byte)0xdf, //E
            (byte)0x8c, (byte)0xa1, (byte)0x89, (byte)0x0d, (byte)0xbf, (byte)0xe6, (byte)0x42, (byte)0x68, (byte)0x41, (byte)0x99, (byte)0x2d, (byte)0x0f, (byte)0xb0, (byte)0x54, (byte)0xbb, (byte)0x16 }; //F

    // inverse sbox
    static public byte[] rsbox = {
            (byte)0x52, (byte)0x09, (byte)0x6a, (byte)0xd5, (byte)0x30, (byte)0x36, (byte)0xa5, (byte)0x38, (byte)0xbf, (byte)0x40, (byte)0xa3, (byte)0x9e, (byte)0x81, (byte)0xf3, (byte)0xd7, (byte)0xfb
            , (byte)0x7c, (byte)0xe3, (byte)0x39, (byte)0x82, (byte)0x9b, (byte)0x2f, (byte)0xff, (byte)0x87, (byte)0x34, (byte)0x8e, (byte)0x43, (byte)0x44, (byte)0xc4, (byte)0xde, (byte)0xe9, (byte)0xcb
            , (byte)0x54, (byte)0x7b, (byte)0x94, (byte)0x32, (byte)0xa6, (byte)0xc2, (byte)0x23, (byte)0x3d, (byte)0xee, (byte)0x4c, (byte)0x95, (byte)0x0b, (byte)0x42, (byte)0xfa, (byte)0xc3, (byte)0x4e
            , (byte)0x08, (byte)0x2e, (byte)0xa1, (byte)0x66, (byte)0x28, (byte)0xd9, (byte)0x24, (byte)0xb2, (byte)0x76, (byte)0x5b, (byte)0xa2, (byte)0x49, (byte)0x6d, (byte)0x8b, (byte)0xd1, (byte)0x25
            , (byte)0x72, (byte)0xf8, (byte)0xf6, (byte)0x64, (byte)0x86, (byte)0x68, (byte)0x98, (byte)0x16, (byte)0xd4, (byte)0xa4, (byte)0x5c, (byte)0xcc, (byte)0x5d, (byte)0x65, (byte)0xb6, (byte)0x92
            , (byte)0x6c, (byte)0x70, (byte)0x48, (byte)0x50, (byte)0xfd, (byte)0xed, (byte)0xb9, (byte)0xda, (byte)0x5e, (byte)0x15, (byte)0x46, (byte)0x57, (byte)0xa7, (byte)0x8d, (byte)0x9d, (byte)0x84
            , (byte)0x90, (byte)0xd8, (byte)0xab, (byte)0x00, (byte)0x8c, (byte)0xbc, (byte)0xd3, (byte)0x0a, (byte)0xf7, (byte)0xe4, (byte)0x58, (byte)0x05, (byte)0xb8, (byte)0xb3, (byte)0x45, (byte)0x06
            , (byte)0xd0, (byte)0x2c, (byte)0x1e, (byte)0x8f, (byte)0xca, (byte)0x3f, (byte)0x0f, (byte)0x02, (byte)0xc1, (byte)0xaf, (byte)0xbd, (byte)0x03, (byte)0x01, (byte)0x13, (byte)0x8a, (byte)0x6b
            , (byte)0x3a, (byte)0x91, (byte)0x11, (byte)0x41, (byte)0x4f, (byte)0x67, (byte)0xdc, (byte)0xea, (byte)0x97, (byte)0xf2, (byte)0xcf, (byte)0xce, (byte)0xf0, (byte)0xb4, (byte)0xe6, (byte)0x73
            , (byte)0x96, (byte)0xac, (byte)0x74, (byte)0x22, (byte)0xe7, (byte)0xad, (byte)0x35, (byte)0x85, (byte)0xe2, (byte)0xf9, (byte)0x37, (byte)0xe8, (byte)0x1c, (byte)0x75, (byte)0xdf, (byte)0x6e
            , (byte)0x47, (byte)0xf1, (byte)0x1a, (byte)0x71, (byte)0x1d, (byte)0x29, (byte)0xc5, (byte)0x89, (byte)0x6f, (byte)0xb7, (byte)0x62, (byte)0x0e, (byte)0xaa, (byte)0x18, (byte)0xbe, (byte)0x1b
            , (byte)0xfc, (byte)0x56, (byte)0x3e, (byte)0x4b, (byte)0xc6, (byte)0xd2, (byte)0x79, (byte)0x20, (byte)0x9a, (byte)0xdb, (byte)0xc0, (byte)0xfe, (byte)0x78, (byte)0xcd, (byte)0x5a, (byte)0xf4
            , (byte)0x1f, (byte)0xdd, (byte)0xa8, (byte)0x33, (byte)0x88, (byte)0x07, (byte)0xc7, (byte)0x31, (byte)0xb1, (byte)0x12, (byte)0x10, (byte)0x59, (byte)0x27, (byte)0x80, (byte)0xec, (byte)0x5f
            , (byte)0x60, (byte)0x51, (byte)0x7f, (byte)0xa9, (byte)0x19, (byte)0xb5, (byte)0x4a, (byte)0x0d, (byte)0x2d, (byte)0xe5, (byte)0x7a, (byte)0x9f, (byte)0x93, (byte)0xc9, (byte)0x9c, (byte)0xef
            , (byte)0xa0, (byte)0xe0, (byte)0x3b, (byte)0x4d, (byte)0xae, (byte)0x2a, (byte)0xf5, (byte)0xb0, (byte)0xc8, (byte)0xeb, (byte)0xbb, (byte)0x3c, (byte)0x83, (byte)0x53, (byte)0x99, (byte)0x61
            , (byte)0x17, (byte)0x2b, (byte)0x04, (byte)0x7e, (byte)0xba, (byte)0x77, (byte)0xd6, (byte)0x26, (byte)0xe1, (byte)0x69, (byte)0x14, (byte)0x63, (byte)0x55, (byte)0x21, (byte)0x0c, (byte)0x7d };

    // round constant
    static public byte[] Rcon = {
            (byte)0x01, (byte)0x02, (byte)0x04, (byte)0x08, (byte)0x10, (byte)0x20, (byte)0x40, (byte)0x80, (byte)0x1b, (byte)0x36 };


    static int toUnsigned(byte val){
        int out=val;
        if (out<0)
            out=val & 0xFF;

        return out;
    }



    // multiply by 2 in the galois field
    static byte galois_mul2(byte value)
    {
        byte tmp=0;
        tmp=(byte)(value >> 7);
        if ((tmp) != 0)
//            if ((value >> 7) > 0)
        {
            return (byte)((value << 1) ^ 0x1b);
        }
        else
            return (byte)(value << 1);
    }

    // AES encryption and decryption function
    // The code was optimized for memory (flash and ram)
    // Combining both encryption and decryption resulted in a slower implementation
    // but much smaller than the 2 functions separated
    // This function only implements AES-128 encryption and decryption (AES-192 and
    // AES-256 are not supported by this code)
    public static void aes_enc_dec(byte[] state, byte[] key, byte dir)
    {
        byte buf1, buf2, buf3, buf4, round, i;

        // In case of decryption
        if (dir > 0)
        {
            // compute the last key of encryption before starting the decryption
            for (round = 0; round < 10; round++)
            {
                //key schedule
                key[0] = (byte)((byte)(sbox[toUnsigned(key[13])] ^ key[0]) ^ Rcon[round]);
                key[1] = (byte)(sbox[toUnsigned(key[14])] ^ key[1]);
                key[2] = (byte)(sbox[toUnsigned(key[15])] ^ key[2]);
                key[3] = (byte)(sbox[toUnsigned(key[12])] ^ key[3]);
                for (i = 4; i < 16; i++)
                {
                    key[i] = (byte)(key[i] ^ key[i - 4]);
                }
            }

            //first Addroundkey
            for (i = 0; i < 16; i++)
            {
                state[i] = (byte)(state[i] ^ key[i]);
            }
        }

        // main loop
        for (round = 0; round < 10; round++)
        {
            if (dir > 0)
            {
                //Inverse key schedule
                for (i = 15; i > 3; --i)
                {
                    key[i] = (byte)(key[i] ^ key[i - 4]);
                }
                key[0] = (byte)((byte)(sbox[toUnsigned(key[13])] ^ key[0]) ^ Rcon[9 - round]);
                key[1] = (byte)(sbox[toUnsigned(key[14])] ^ key[1]);
                key[2] = (byte)(sbox[toUnsigned(key[15])] ^ key[2]);
                key[3] = (byte)(sbox[toUnsigned(key[12])] ^ key[3]);
            }
            else
            {
                for (i = 0; i < 16; i++)
                {
                    // with shiftrow i+5 mod 16
                    state[i] = sbox[toUnsigned((byte)(state[i] ^ key[i]))];
                }
                //shift rows
                buf1 = state[1];
                state[1] = state[5];
                state[5] = state[9];
                state[9] = state[13];
                state[13] = buf1;

                buf1 = state[2];
                buf2 = state[6];
                state[2] = state[10];
                state[6] = state[14];
                state[10] = buf1;
                state[14] = buf2;

                buf1 = state[15];
                state[15] = state[11];
                state[11] = state[7];
                state[7] = state[3];
                state[3] = buf1;
            }
            //mixcol - inv mix
            if ((round > 0 && dir > 0) || (round < 9 && !(dir > 0)))
            {
                for (i = 0; i < 4; i++)
                {
                    buf4 = (byte)(i << 2);
                    if (dir > 0)
                    {
                        // precompute for decryption
                        buf1 = galois_mul2(galois_mul2((byte)(state[buf4] ^ state[buf4 + 2])));
                        buf2 = galois_mul2(galois_mul2((byte)(state[buf4 + 1] ^ state[buf4 + 3])));
                        state[buf4] ^= buf1; state[buf4 + 1] ^= buf2; state[buf4 + 2] ^= buf1; state[buf4 + 3] ^= buf2;
                    }
                    // in all cases
                    buf1 = (byte)((byte)((byte)(state[buf4] ^ state[buf4 + 1]) ^ state[buf4 + 2]) ^ state[buf4 + 3]);
                    buf2 = state[buf4];
                    buf3 = (byte)(state[buf4] ^ state[toUnsigned(buf4) + 1]);

                    buf3 = galois_mul2(buf3);
                    state[buf4] = (byte)((byte)(state[buf4] ^ buf3) ^ buf1);

                    buf3 = (byte)(state[toUnsigned(buf4) + 1] ^ state[toUnsigned(buf4) + 2]);
                    buf3 = galois_mul2(buf3);
                    state[toUnsigned(buf4) + 1] = (byte)((byte)(state[toUnsigned(buf4) + 1] ^ buf3) ^ buf1);

                    buf3 = (byte)(state[toUnsigned(buf4) + 2] ^ state[toUnsigned(buf4) + 3]); buf3 = galois_mul2(buf3); state[toUnsigned(buf4) + 2] = (byte)((byte)(state[toUnsigned(buf4) + 2] ^ buf3) ^ buf1);
                    buf3 = (byte)(state[toUnsigned(buf4) + 3] ^ buf2); buf3 = galois_mul2(buf3); state[toUnsigned(buf4) + 3] = (byte)((byte)(state[toUnsigned(buf4) + 3] ^ buf3) ^ buf1);
                }
            }

            if (dir > 0)
            {
                //Inv shift rows
                // Row 1
                buf1 = state[13];
                state[13] = state[9];
                state[9] = state[5];
                state[5] = state[1];
                state[1] = buf1;
                //Row 2
                buf1 = state[10];
                buf2 = state[14];
                state[10] = state[2];
                state[14] = state[6];
                state[2] = buf1;
                state[6] = buf2;
                //Row 3
                buf1 = state[3];
                state[3] = state[7];
                state[7] = state[11];
                state[11] = state[15];
                state[15] = buf1;

                for (i = 0; i < 16; i++)
                {
                    // with shiftrow i+5 mod 16
                    state[i] = (byte)(rsbox[toUnsigned(state[i])] ^ key[i]);
                }
            }
            else
            {
                //key schedule
                key[0] = (byte)((byte)(sbox[toUnsigned(key[13])] ^ key[0]) ^ Rcon[round]);
                key[1] = (byte)(sbox[toUnsigned(key[14])] ^ key[1]);
                key[2] = (byte)(sbox[toUnsigned(key[15])] ^ key[2]);
                key[3] = (byte)(sbox[toUnsigned(key[12])] ^ key[3]);
                for (i = 4; i < 16; i++)
                {
                    key[i] = (byte)(key[i] ^ key[i - 4]);
                }
            }
        }
        if (!(dir > 0))
        {
            //last Addroundkey
            for (i = 0; i < 16; i++)
            {
                // with shiftrow i+5 mod 16
                state[i] = (byte)(state[i] ^ key[i]);
            } // enf for
        } // end if (!dir)
    } // end function
}
