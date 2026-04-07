/**
 * @file features/auth/SignupPage.tsx
 * @description 신규 회원가입을 위한 페이지 컴포넌트입니다.
 * 아이디, 이름, 비밀번호 입력 폼과 회원가입 API 연동 로직을 포함합니다.
 */
import { ArrowRight, Lock, User, UserPlus } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { useAuth } from './AuthContext';

export function SignupPage() {
  const navigate = useNavigate();
  const { signup } = useAuth(); // AuthContext에서 회원가입 함수 가져옴

  // 폼 상태 관리
  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  // 폼 제출 핸들러
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');

    try {
      await signup({ userId, password, name }); // 회원가입 API 호출
      alert('회원가입이 완료되었습니다. 로그인해주세요.');
      navigate('/login'); // 성공 시 로그인 페이지로 이동
    } catch (err) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('회원가입에 실패했습니다.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className='min-h-screen w-full flex items-center justify-center relative overflow-hidden bg-gray-50'>
      {/* 배경 장식 요소 */}
      <div className='absolute inset-0 z-0'>
        <div className='absolute top-[-10%] left-[-10%] w-[40%] h-[40%] rounded-full bg-primary/20 blur-[120px] animate-pulse' />
        <div className='absolute bottom-[-10%] right-[-10%] w-[40%] h-[40%] rounded-full bg-indigo-400/20 blur-[120px] animate-pulse delay-1000' />
        <div className="absolute top-[20%] left-[20%] w-[60%] h-[60%] bg-[url('https://images.unsplash.com/photo-1497366216548-37526070297c?auto=format&fit=crop&w=1920&q=80')] bg-cover opacity-[0.03]" />
      </div>

      <div className='relative z-10 w-full max-w-110 px-6 animate-fade-in'>
        {/* 회원가입 카드 */}
        <div className='bg-white/70 backdrop-blur-2xl rounded-3xl shadow-2xl border border-white/50 p-8 md:p-10 ring-1 ring-gray-900/5'>
          {/* 헤더 섹션 */}
          <div className='text-center mb-10'>
            <div className='mx-auto w-14 h-14 bg-primary/10 rounded-2xl flex items-center justify-center mb-6 text-primary'>
              <UserPlus className='w-8 h-8' />
            </div>
            <h1 className='text-3xl font-bold tracking-tight text-gray-900'>Sign Up</h1>
            <p className='mt-3 text-gray-500 text-sm font-medium'>새로운 계정을 생성하세요</p>
          </div>

          {/* 회원가입 폼 */}
          <form onSubmit={handleSubmit} className='space-y-6'>
            <div className='space-y-5'>
              <Input
                label='아이디'
                placeholder='사용할 아이디를 입력하세요'
                value={userId}
                onChange={(e) => setUserId(e.target.value)}
                required
                leftIcon={<User size={18} />}
                className='bg-white/50'
              />
              <Input
                label='이름'
                placeholder='사용자 이름을 입력하세요'
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                leftIcon={<User size={18} />}
                className='bg-white/50'
              />
              <Input
                label='비밀번호'
                type='password'
                placeholder='비밀번호를 입력하세요'
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                leftIcon={<Lock size={18} />}
                className='bg-white/50'
              />
            </div>

            {/* 에러 메시지 */}
            {error && (
              <div className='p-3 rounded-xl bg-red-50 border border-red-100 flex items-center gap-3 text-sm text-red-600 animate-slide-up'>
                <svg className='w-5 h-5 shrink-0' fill='none' viewBox='0 0 24 24' stroke='currentColor'>
                  <path
                    strokeLinecap='round'
                    strokeLinejoin='round'
                    strokeWidth={2}
                    d='M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z'
                  />
                </svg>
                {error}
              </div>
            )}

            <Button
              type='submit'
              className='w-full h-14 text-base font-semibold rounded-xl mt-2 group relative overflow-hidden transition-all duration-300 hover:-translate-y-1 hover:shadow-xl'
              isLoading={isLoading}
            >
              <span className='relative z-10 flex items-center justify-center gap-2'>
                가입하기
                <ArrowRight size={18} className='group-hover:translate-x-1 transition-transform' />
              </span>
            </Button>
          </form>

          {/* 푸터 */}
          <div className='mt-8 pt-6 border-t border-gray-100 text-center'>
            <p className='text-sm text-gray-500'>
              이미 계정이 있으신가요?{' '}
              <button
                type='button'
                className='font-semibold text-primary hover:text-primary-hover transition-colors cursor-pointer hover:underline'
                onClick={() => navigate('/login')}
              >
                로그인하기
              </button>
            </p>
          </div>
        </div>

        <p className='text-center mt-8 text-xs text-gray-400 font-medium'>© 2024 Your Company. All rights reserved.</p>
      </div>
    </div>
  );
}
