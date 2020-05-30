#include "mbed.h"
#include <stdlib.h>
#include <string.h>

void USART2_init(void);
void DMA1_init(void);
void DMA1_Stream6_setup(unsigned int src, unsigned int dst, int len);

int done = 1;

int main (void)
{
    char alphabets[80];
    char message[80];
    
    while(1){
        
        RawSerial pc(PA_2, PA_3, 115200);
        pc.printf("Enter Fibonacci number n: \r\n");
        char* buf = (char*)malloc(sizeof(char)*8);
        int size = 0;
        
        while(true) {
                    char ch = pc.getc();
                    pc.putc(ch);
                    if (ch == 0x0D) {
                        while(!pc.writeable());
                        pc.putc(0x0A);
                        break;
                    }
                    buf[size] = ch;
                        size++;
        }
        pc.printf("pc : %s\n",buf);
        
        USART2_init();
        DMA1_init();
        
        int val = atoi(buf);
        int arrSize = val + 2;
        free(buf);
        
        if(val < 0 || val > 40) {
            strcpy(alphabets,"ERROR: must be satisfied with '0 <= n <= 40'\r\n");
            size = sizeof(alphabets);
            DMA1_Stream6_setup((unsigned int)alphabets, (unsigned int)&USART2->DR, size);

            continue;
        }
        
        
        buf = (char*)malloc(sizeof(char)*256);
//////
        int *arr = (int*)malloc(sizeof(int)*arrSize);
        
        for(int i = 0; i < 4; i++){
            buf[i] = 32;
        }
        
        arr[0] = 0;
        
        buf[4] = 0 + '0';
        buf[5] = 32;
        
        arr[1] = 1;
        
        buf[6] = 49;
        buf[7] = 32;
        
        int buffIndex = 9;
        
        for(int i = 2; i < arrSize; i++) {
            
            arr[i] = arr[i-1] + arr[i-2];
        }
        //        arr = {0, 1, 1, 2, 3, 5, ...}

        for(int i = 2; i < arrSize; i++){
            int number = arr[i];
            
            while (number != 0)
            {
                buf[buffIndex] = (number%10) + '0';
                buffIndex++;
                number /= 10;
            }
            
            if((i % 5) == 4){
                buf[buffIndex] = 10;
                buffIndex++;
            }else{
                buf[buffIndex] = 32;
                buffIndex++;
            }
        }
        if(val == 0) buffIndex = 7;
        if(val == 1) buffIndex = 9;
        
        DMA1_Stream6_setup((unsigned int)buf, (unsigned int)&USART2->DR, buffIndex);
        
        free(buf);
    }
    
}

void USART2_init (void) {
    RCC->AHB1ENR |= 1;          /* enable GPIOA clock */
    RCC->APB1ENR |= 0x20000;    /* enable USART2 clock */

    /* Configure PA2 for USART2_TX */
    GPIOA->AFR[0] &= ~0x0F00;
    GPIOA->AFR[0] |=  0x0700;   /* alt7 for USART2 */
    GPIOA->MODER  &= ~0x0030;
    GPIOA->MODER  |=  0x0020;   /* enable alternate function for PA2 */

    USART2->BRR = 434;       /* 9600 baud @ 16 MHz */
    USART2->CR1 = 0x0008;       /* enable Tx, 8-bit data */
    USART2->CR2 = 0x0000;       /* 1 stop bit */
    USART2->CR3 = 0x0000;       /* no flow control */
    USART2->CR1 |= 0x2000;      /* enable USART2 */
      
    USART2->SR = ~0x40;         /* clear TC flag */
    USART2->CR1 |= 0x0040;      /* enable transmit complete interrupt */

    NVIC_EnableIRQ(USART2_IRQn);    /* USART2 interrupt enable at NVIC */
}

void DMA1_init(void) {
    RCC->AHB1ENR |= 0x00200000;     /* DMA controller clock enable */
    DMA1->HIFCR = 0x003F0000;       /* clear all interrupt flags of Stream 6 */
    NVIC_EnableIRQ(DMA1_Stream6_IRQn);  /* DMA interrupt enable at NVIC */
}

/*  Set up a DMA transfer for USART2 
 *  The USART2 is connected to DMA1 Stream 6. This function sets up the 
 *  peripheral register address, memory address, number of transfers,
 *  data size, transfer direction, and DMA interrupts are enabled.
 *  At the end, the DMA controller is enabled and the USART2 transmit
 *  DMA is enabled.
 */
void DMA1_Stream6_setup(unsigned int src, unsigned int dst, int len) {
    DMA1_Stream6->CR &= ~1;         /* disable DMA1 Stream 6 */
    while (DMA1_Stream6->CR & 1) {} /* wait until DMA1 Stream 6 is disabled */
    DMA1->HIFCR = 0x003F0000;       /* clear all interrupt flags of Stream 6 */
    DMA1_Stream6->PAR = dst;
    DMA1_Stream6->M0AR = src;
    DMA1_Stream6->NDTR = len;
    DMA1_Stream6->CR = 0x08000000;  /* USART2_TX on DMA1 Stream6 Channel 4 */
    DMA1_Stream6->CR |= 0x00000440; /* data size byte, mem incr, mem-to-peripheral */
    DMA1_Stream6->CR |= 0x16;       /* enable interrupts DMA_IT_TC | DMA_IT_TE | DMA_IT_DME */
    DMA1_Stream6->FCR  = 0;         /* direct mode, no FIFO */
    DMA1_Stream6->CR |= 1;          /* enable DMA1 Stream 6 */

    USART2->SR &= ~0x0040;          /* clear UART transmit complete interrupt flag */
    USART2->CR3 |= 0x80;            /* enable USART2 transmitter DMA */
}

/*  DMA1 Stream6 interrupt handler
    This function handles the interrupts from DMA1 controller Stream6. The error interrupts 
    have a placeholder for error handling code. If the interrupt is from DMA data
    transfer complete, the DMA controller is disabled, the interrupt flags are
    cleared.
 */
void DMA1_Stream6_IRQHandler(void)
{
    if (DMA1->HISR & 0x000C0000)    /* if an error occurred */
        while(1) {}                 /* substitute this by error handling */
    DMA1->HIFCR = 0x003F0000;       /* clear all interrupt flags of Stream 6 */
    DMA1_Stream6->CR &= ~0x10;      /* disable DMA1 Stream 6 TCIE */
}

/*  USART2 interrupt handler
 *  USART2 transmit complete interrupt is used to set the done flag to signal
 *  the other part of the program that the data transfer is done.
 */
void USART2_IRQHandler(void)
{
    USART2->SR &= ~0x0040;          /* clear transmit complete interrupt flag */
    done = 1;                       /* set the done flag */
}
