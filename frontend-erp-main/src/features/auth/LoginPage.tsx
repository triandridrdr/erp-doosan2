/**
 * @file features/auth/LoginPage.tsx
 * @description DCBJ ERP login page. Clean white card centered on a lavender-gray
 * background, matching the PPT design (DCBJ logo, ID/Password labels, Sign in).
 */
import { Lock, User } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { useAuth } from './AuthContext';

export function LoginPage() {
  const navigate = useNavigate();
  const { login } = useAuth();

  const [userId, setUserId] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [isLoading, setIsLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setIsLoading(true);
    setError('');
    try {
      await login({ userId, password });
      navigate('/');
    } catch (err) {
      if (err instanceof Error) setError(err.message);
      else setError('Login failed. Please check your user ID and password.');
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className='min-h-screen w-full flex items-center justify-center bg-background px-4'>
      <div className='w-full max-w-md animate-fade-in'>
        <div className='bg-white rounded-2xl shadow-md border border-gray-100 p-10'>
          {/* DCBJ logo */}
          <div className='flex justify-center mb-10'>
            <svg viewBox='0 0 120 60' className='w-28 h-14' aria-hidden='true'>
              <text
                x='0'
                y='40'
                fontFamily='Arial, sans-serif'
                fontWeight='900'
                fontSize='42'
                fill='#0E4D92'
                letterSpacing='-2'
              >
                DCS
              </text>
              <text
                x='40'
                y='55'
                fontFamily='Arial, sans-serif'
                fontWeight='700'
                fontSize='8'
                fill='#0E4D92'
                letterSpacing='1'
              >
                DCBJ
              </text>
            </svg>
          </div>

          <form onSubmit={handleSubmit} className='space-y-4'>
            <div>
              <label className='block text-xs font-medium text-gray-700 mb-1'>ID</label>
              <Input
                placeholder='Please enter your ID'
                value={userId}
                onChange={(e) => setUserId(e.target.value)}
                required
                leftIcon={<User size={16} />}
              />
            </div>
            <div>
              <label className='block text-xs font-medium text-gray-700 mb-1'>Password</label>
              <Input
                type='password'
                placeholder='Please enter your password'
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                leftIcon={<Lock size={16} />}
              />
            </div>

            {error && (
              <div className='p-2.5 rounded-md bg-red-50 border border-red-200 text-sm text-red-700 animate-slide-up'>
                {error}
              </div>
            )}

            <Button
              type='submit'
              className='w-full h-11 mt-4 text-sm font-semibold rounded-md'
              isLoading={isLoading}
            >
              Sign in
            </Button>
          </form>

          <div className='mt-6 text-center'>
            <button
              type='button'
              className='text-xs font-medium text-primary hover:text-primary-hover transition-colors cursor-pointer hover:underline'
              onClick={() => navigate('/signup')}
            >
              Create an account
            </button>
          </div>
        </div>

        <p className='text-center mt-6 text-xs text-gray-400'>© DCBJ ERP. All rights reserved.</p>
      </div>
    </div>
  );
}
