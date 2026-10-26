import { describe, expect, it } from 'vitest';
import { parseNotification } from './parser.js';

// Mẫu GIẢ ĐỊNH. Thay bằng mẫu thật thu từ app Android (che bớt số TK).
describe('parseNotification', () => {
  it('tiền vào có số dư và nội dung', () => {
    const r = parseNotification(
      'TK 0123xxx789|GD: +5,000,000VND 26/10/26 13:54 |SD: 12,345,678VND|ND: NGUYEN VAN A chuyen tien',
    );
    expect(r).toEqual({
      amount: 5_000_000,
      direction: 'in',
      balanceAfter: 12_345_678,
      account: '0123xxx789',
      description: 'NGUYEN VAN A chuyen tien',
      transactionTime: '2026-10-26T06:54:00.000Z',
    });
  });

  it('tiền ra', () => {
    const r = parseNotification('TK 0123xxx789|GD: -150,000VND 01/11/26 08:05 |SD: 12,195,678VND|ND: thanh toan QR');
    expect(r?.direction).toBe('out');
    expect(r?.amount).toBe(150_000);
    expect(r?.balanceAfter).toBe(12_195_678);
  });

  it('không phải giao dịch → null', () => {
    expect(parseNotification('Ma OTP cua quy khach la 123456')).toBeNull();
    expect(parseNotification('Uu dai hoan tien 50% khi thanh toan QR')).toBeNull();
  });

  it('thiếu dấu +/- → null, không đoán', () => {
    expect(parseNotification('TK 0123xxx789|GD: 150,000VND')).toBeNull();
  });
});
