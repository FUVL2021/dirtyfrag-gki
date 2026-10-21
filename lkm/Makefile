# dfroot LKM
#
# One object per GKI KMI. The source is version agnostic, so the only thing
# that changes between builds is the kernel tree it is compiled against
# (which supplies vermagic and the struct layouts).
#
# Size diet matters: this .ko is written into a vendor library page by page
# through the exploit, so every byte travels. -Os shrinks the code and
# dropping the unwind tables removes .eh_frame, which modules never use.

obj-m += dfroot.o

CFLAGS_dfroot.o := -Os -fno-asynchronous-unwind-tables -fno-unwind-tables

KDIR ?= /lib/modules/$(shell uname -r)/build
CROSS ?= aarch64-linux-gnu-

all:
	$(MAKE) -C $(KDIR) M=$(CURDIR) \
		ARCH=arm64 \
		CROSS_COMPILE=$(CROSS) \
		LLVM=1 \
		modules

clean:
	$(MAKE) -C $(KDIR) M=$(CURDIR) clean
	rm -f dfroot.lds

.PHONY: all clean