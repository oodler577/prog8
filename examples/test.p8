%import c64textio
%zeropage basicsafe

main {
    sub start() {

            uword xx
            ubyte yy
                for xx in 319 downto 0 {
                    ; TODO also fix that the asm is invalid for word iterator variable.
                }


            for yy in 199 downto 0 {
                for xx in 319 downto 0 {
                    ; TODO also fix that the asm is invalid for word iterator variable.
                }
            }



        byte b1
        byte b2
        byte b3

        word w1
        word w2
        word w3


        b2 = 13
        b3 = 100
        b1 = b3 / b2
        txt.print_b(b1)
        c64.CHROUT('\n')

        b2 = -13
        b3 = 100
        b1 = b3 / b2
        txt.print_b(b1)
        c64.CHROUT('\n')

        b2 = 13
        b3 = -100
        b1 = b3 / b2
        txt.print_b(b1)
        c64.CHROUT('\n')

        b2 = -13
        b3 = -100
        b1 = b3 / b2
        txt.print_b(b1)
        c64.CHROUT('\n')


        b2 = 13
        b3 = 100
        b3 /= b2
        txt.print_b(b3)
        c64.CHROUT('\n')

        b2 = -13
        b3 = 100
        b3 /= b2
        txt.print_b(b3)
        c64.CHROUT('\n')

        b2 = 13
        b3 = -100
        b3 /= b2
        txt.print_b(b3)
        c64.CHROUT('\n')

        b2 = -13
        b3 = -100
        b3 /= b2
        txt.print_b(b3)
        c64.CHROUT('\n')
        c64.CHROUT('\n')





        w2 = 133
        w3 = 20000
        w1 = w3 / w2
        txt.print_w(w1)
        c64.CHROUT('\n')

        w2 = -133
        w3 = 20000
        w1 = w3 / w2
        txt.print_w(w1)
        c64.CHROUT('\n')

        w2 = 133
        w3 = -20000
        w1 = w3 / w2
        txt.print_w(w1)
        c64.CHROUT('\n')

        w2 = -133
        w3 = -20000
        w1 = w3 / w2
        txt.print_w(w1)
        c64.CHROUT('\n')


        w2 = 133
        w3 = 20000
        w3 /= w2
        txt.print_w(w3)
        c64.CHROUT('\n')

        w2 = -133
        w3 = 20000
        w3 /= w2
        txt.print_w(w3)
        c64.CHROUT('\n')

        w2 = 133
        w3 = -20000
        w3 /= w2
        txt.print_w(w3)
        c64.CHROUT('\n')

        w2 = -133
        w3 = -20000
        w3 /= w2
        txt.print_w(w3)
        c64.CHROUT('\n')
    }
}
