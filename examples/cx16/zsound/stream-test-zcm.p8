%import textio
%import diskio
%import cx16diskio
%zpreserved $22,$2d     ; zsound lib uses this region


main $0830 {

zsound_lib:
    ; this has to be the first statement to make sure it loads at the specified module address $0830
    %asmbinary "zsound_combo-0830.bin"

    ; note: jump table is offset by 2 from the load address (because of prg header)
    romsub $0832 = zsm_init() clobbers(A)
    romsub $0835 = zsm_play() clobbers(A, X, Y)
    romsub $0838 = zsm_playIRQ() clobbers(A, X, Y)
    romsub $083b = zsm_start(ubyte bank @A, uword song_address @XY) clobbers(A, X, Y) -> ubyte @Pc
    romsub $083e = zsm_stop()
    romsub $0841 = zsm_setspeed(uword hz @XY) clobbers(A, X, Y)
    romsub $0844 = zsm_setloop(ubyte count @A)
    romsub $0847 = zsm_forceloop(ubyte count @A)
    romsub $084a = zsm_noloop()
    romsub $084d = zsm_setcallback(uword address @XY)
    romsub $0850 = zsm_clearcallback() clobbers(A)
    romsub $0853 = zsm_get_music_speed() clobbers(A) -> uword @XY
    romsub $0856 = pcm_init() clobbers(A)
    romsub $0859 = pcm_trigger_digi(ubyte bank @A, uword song_address @XY)
    romsub $085c = pcm_play() clobbers(A, X, Y)
    romsub $085f = pcm_stop() clobbers(A)
    romsub $0862 = pcm_set_volume(ubyte volume @A)

    const ubyte digi_bank = 1
    const uword digi_address = $a000
    const ubyte zcm_DIGITAB_size = 8        ; header size
    const uword ram_bank_size = $2000

    ubyte load_ok = false

    sub prebuffer()  {
        txt.print("prebuffering...")
        void cx16diskio.f_read(digi_address, ram_bank_size*4)
    }

    sub start() {
        txt.print("\nzsound digi streaming!\n")

        if not diskio.f_open(8, "thriller.zcm") {
            txt.print("?no file\n")
            return
        }

        cx16.rambank(digi_bank)
        prebuffer()

        pcm_init()
        pcm_trigger_digi(digi_bank, digi_address)
        cx16.set_irq(&zsm_playroutine, true)

        txt.print("\nstreaming from file, playback in irq!\n")
        uword size = 1
        while size {
            size = cx16diskio.f_read(digi_address, ram_bank_size)       ; load next bank
            txt.print_ub(cx16.getrambank())
            txt.spc()
        }

        txt.print("file end.\n")
        diskio.f_close()

        repeat {
        }

        pcm_stop()  ;unreached
    }

    sub zsm_playroutine() {
        pcm_play()
    }
}